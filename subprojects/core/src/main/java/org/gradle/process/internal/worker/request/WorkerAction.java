/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.process.internal.worker.request;

import org.gradle.api.Action;
import org.gradle.cache.internal.DefaultCrossBuildInMemoryCacheFactory;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.concurrent.Stoppable;
import org.gradle.internal.dispatch.StreamCompletion;
import org.gradle.internal.event.DefaultListenerManager;
import org.gradle.internal.instantiation.generator.DefaultInstantiatorFactory;
import org.gradle.internal.instantiation.InstantiatorFactory;
import org.gradle.internal.operations.CurrentBuildOperationRef;
import org.gradle.internal.remote.ObjectConnection;
import org.gradle.internal.remote.internal.hub.StreamFailureHandler;
import org.gradle.internal.service.DefaultServiceRegistry;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.process.internal.worker.WorkerProcessContext;

import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;

public class WorkerAction implements Action<WorkerProcessContext>, Serializable, RequestProtocol, StreamFailureHandler, Stoppable, StreamCompletion {
    private final String workerImplementationName;
    private transient CountDownLatch completed;
    private transient ResponseProtocol responder;
    private transient Throwable failure;
    private transient Class<?> workerImplementation;
    private transient Object implementation;
    private InstantiatorFactory instantiatorFactory;

    public WorkerAction(Class<?> workerImplementation) {
        this.workerImplementationName = workerImplementation.getName();
    }

    @Override
    public void execute(WorkerProcessContext workerProcessContext) {
        completed = new CountDownLatch(1);
        try {
            ServiceRegistry parentServices = workerProcessContext.getServiceRegistry();
            if (instantiatorFactory == null) {
                instantiatorFactory = new DefaultInstantiatorFactory(new DefaultCrossBuildInMemoryCacheFactory(new DefaultListenerManager()), Collections.emptyList());
            }
            DefaultServiceRegistry serviceRegistry = new DefaultServiceRegistry("worker-action-services", parentServices);
            // Make the argument serializers available so work implementations can register their own serializers
            RequestArgumentSerializers argumentSerializers = new RequestArgumentSerializers();
            serviceRegistry.add(RequestArgumentSerializers.class, argumentSerializers);
            serviceRegistry.add(InstantiatorFactory.class, instantiatorFactory);
            workerProcessContext.getServerConnection().useParameterSerializers(RequestSerializerRegistry.create(this.getClass().getClassLoader(), argumentSerializers));
            workerImplementation = Class.forName(workerImplementationName);
            implementation = instantiatorFactory.inject(serviceRegistry).newInstance(workerImplementation);
        } catch (Throwable e) {
            failure = e;
        }

        ObjectConnection connection = workerProcessContext.getServerConnection();
        connection.addIncoming(RequestProtocol.class, this);
        responder = connection.addOutgoing(ResponseProtocol.class);
        connection.connect();

        try {
            completed.await();
        } catch (InterruptedException e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
    }

    @Override
    public void stop() {
        completed.countDown();
        CurrentBuildOperationRef.instance().clear();
    }

    @Override
    public void endStream() {
        // This happens when the connection between the worker and the build daemon is closed for some reason,
        // possibly because the build daemon died unexpectedly.
        stop();
    }

    @Override
    public void runThenStop(Request request) {
        try {
            run(request);
        } finally {
            stop();
        }
    }

    @Override
    public void run(Request request) {
        if (failure != null) {
            responder.infrastructureFailed(failure);
            return;
        }
        try {
            Method method = workerImplementation.getDeclaredMethod(request.getMethodName(), request.getParamTypes());
            CurrentBuildOperationRef.instance().set(request.getBuildOperation());
            Object result;
            try {
                result = method.invoke(implementation, request.getArgs());
            } catch (InvocationTargetException e) {
                Throwable failure = e.getCause();
                if (failure instanceof NoClassDefFoundError) {
                    // Assume an infrastructure problem
                    responder.infrastructureFailed(failure);
                } else {
                    responder.failed(failure);
                }
                return;
            }
            responder.completed(result);
        } catch (Throwable t) {
            responder.infrastructureFailed(t);
        } finally {
            CurrentBuildOperationRef.instance().clear();
        }
    }

    @Override
    public void handleStreamFailure(Throwable t) {
        responder.failed(t);
    }
}
