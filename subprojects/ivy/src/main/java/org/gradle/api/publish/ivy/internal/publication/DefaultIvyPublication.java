/*
 * Copyright 2012 the original author or authors.
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

package org.gradle.api.publish.ivy.internal.publication;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Sets;
import org.gradle.api.Action;
import org.gradle.api.DomainObjectSet;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.DependencyArtifact;
import org.gradle.api.artifacts.DependencyConstraint;
import org.gradle.api.artifacts.ExcludeRule;
import org.gradle.api.artifacts.ExternalDependency;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.artifacts.PublishArtifact;
import org.gradle.api.capabilities.Capability;
import org.gradle.api.component.SoftwareComponent;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.CollectionCallbackActionDecorator;
import org.gradle.api.internal.CompositeDomainObjectSet;
import org.gradle.api.internal.FeaturePreviews;
import org.gradle.api.internal.artifacts.DefaultModuleVersionIdentifier;
import org.gradle.api.internal.artifacts.dsl.dependencies.PlatformSupport;
import org.gradle.api.internal.artifacts.ivyservice.projectmodule.ProjectDependencyPublicationResolver;
import org.gradle.api.internal.attributes.AttributeContainerInternal;
import org.gradle.api.internal.attributes.ImmutableAttributes;
import org.gradle.api.internal.attributes.ImmutableAttributesFactory;
import org.gradle.api.internal.component.IvyPublishingAwareContext;
import org.gradle.api.internal.component.MavenPublishingAwareContext;
import org.gradle.api.internal.component.SoftwareComponentInternal;
import org.gradle.api.internal.component.UsageContext;
import org.gradle.api.internal.file.FileCollectionFactory;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.publish.VersionMappingStrategy;
import org.gradle.api.publish.internal.CompositePublicationArtifactSet;
import org.gradle.api.publish.internal.DefaultPublicationArtifactSet;
import org.gradle.api.publish.internal.PublicationArtifactSet;
import org.gradle.api.publish.internal.validation.PublicationWarningsCollector;
import org.gradle.api.publish.internal.versionmapping.VersionMappingStrategyInternal;
import org.gradle.api.publish.ivy.IvyArtifact;
import org.gradle.api.publish.ivy.IvyConfigurationContainer;
import org.gradle.api.publish.ivy.IvyModuleDescriptorSpec;
import org.gradle.api.publish.ivy.internal.artifact.DefaultIvyArtifactSet;
import org.gradle.api.publish.ivy.internal.artifact.DerivedIvyArtifact;
import org.gradle.api.publish.ivy.internal.artifact.SingleOutputTaskIvyArtifact;
import org.gradle.api.publish.ivy.internal.dependency.DefaultIvyDependency;
import org.gradle.api.publish.ivy.internal.dependency.DefaultIvyDependencySet;
import org.gradle.api.publish.ivy.internal.dependency.DefaultIvyExcludeRule;
import org.gradle.api.publish.ivy.internal.dependency.IvyDependencyInternal;
import org.gradle.api.publish.ivy.internal.dependency.IvyExcludeRule;
import org.gradle.api.publish.ivy.internal.publisher.IvyNormalizedPublication;
import org.gradle.api.publish.ivy.internal.publisher.IvyPublicationIdentity;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.internal.Describables;
import org.gradle.internal.DisplayName;
import org.gradle.internal.Factory;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.internal.typeconversion.NotationParser;
import org.gradle.util.GUtil;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.io.File;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.gradle.api.internal.component.MavenPublishingAwareContext.ScopeMapping.compile;
import static org.gradle.api.internal.component.MavenPublishingAwareContext.ScopeMapping.runtime;

public class DefaultIvyPublication implements IvyPublicationInternal {

    private final static Logger LOG = Logging.getLogger(DefaultIvyPublication.class);

    @VisibleForTesting
    public static final String UNSUPPORTED_FEATURE = " contains dependencies that cannot be represented in a published ivy descriptor.";

    private final String name;
    private final IvyModuleDescriptorSpecInternal descriptor;
    private final IvyPublicationIdentity publicationIdentity;
    private final IvyConfigurationContainer configurations;
    private final DefaultIvyArtifactSet mainArtifacts;
    private final PublicationArtifactSet<IvyArtifact> metadataArtifacts;
    private final PublicationArtifactSet<IvyArtifact> derivedArtifacts;
    private final PublicationArtifactSet<IvyArtifact> publishableArtifacts;
    private final DefaultIvyDependencySet ivyDependencies;
    private final ProjectDependencyPublicationResolver projectDependencyResolver;
    private final PlatformSupport platformSupport;
    private final ImmutableAttributesFactory immutableAttributesFactory;
    private final VersionMappingStrategyInternal versionMappingStrategy;
    private IvyArtifact ivyDescriptorArtifact;
    private TaskProvider<? extends Task> moduleDescriptorGenerator;
    private SingleOutputTaskIvyArtifact gradleModuleDescriptorArtifact;
    private SoftwareComponentInternal component;
    private boolean alias;
    private Set<IvyExcludeRule> globalExcludes = new LinkedHashSet<IvyExcludeRule>();
    private boolean populated;
    private boolean artifactsOverridden;
    private boolean versionMappingInUse = false;

    @Inject
    public DefaultIvyPublication(
        String name, Instantiator instantiator, ObjectFactory objectFactory, IvyPublicationIdentity publicationIdentity, NotationParser<Object, IvyArtifact> ivyArtifactNotationParser,
        ProjectDependencyPublicationResolver projectDependencyResolver, FileCollectionFactory fileCollectionFactory,
        ImmutableAttributesFactory immutableAttributesFactory, FeaturePreviews featurePreviews,
        CollectionCallbackActionDecorator collectionCallbackActionDecorator, VersionMappingStrategyInternal versionMappingStrategy, PlatformSupport platformSupport) {
        this.name = name;
        this.publicationIdentity = publicationIdentity;
        this.projectDependencyResolver = projectDependencyResolver;
        this.platformSupport = platformSupport;
        this.configurations = instantiator.newInstance(DefaultIvyConfigurationContainer.class, instantiator, collectionCallbackActionDecorator);
        this.immutableAttributesFactory = immutableAttributesFactory;
        this.versionMappingStrategy = versionMappingStrategy;
        this.mainArtifacts = instantiator.newInstance(DefaultIvyArtifactSet.class, name, ivyArtifactNotationParser, fileCollectionFactory, collectionCallbackActionDecorator);
        this.metadataArtifacts = new DefaultPublicationArtifactSet<>(IvyArtifact.class, "metadata artifacts for " + name, fileCollectionFactory, collectionCallbackActionDecorator);
        this.derivedArtifacts = new DefaultPublicationArtifactSet<>(IvyArtifact.class, "derived artifacts for " + name, fileCollectionFactory, collectionCallbackActionDecorator);
        this.publishableArtifacts = new CompositePublicationArtifactSet<>(IvyArtifact.class, mainArtifacts, metadataArtifacts, derivedArtifacts);
        this.ivyDependencies = instantiator.newInstance(DefaultIvyDependencySet.class, collectionCallbackActionDecorator);
        this.descriptor = instantiator.newInstance(DefaultIvyModuleDescriptorSpec.class, this, instantiator, objectFactory);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public DisplayName getDisplayName() {
        return Describables.withTypeAndName("Ivy publication", name);
    }

    @Override
    public boolean isLegacy() {
        return false;
    }

    @Nullable
    @Override
    public SoftwareComponentInternal getComponent() {
        return component;
    }

    @Override
    public IvyModuleDescriptorSpecInternal getDescriptor() {
        return descriptor;
    }

    @Override
    public void setIvyDescriptorGenerator(TaskProvider<? extends Task> descriptorGenerator) {
        if (ivyDescriptorArtifact != null) {
            metadataArtifacts.remove(ivyDescriptorArtifact);
        }
        ivyDescriptorArtifact = new SingleOutputTaskIvyArtifact(descriptorGenerator, publicationIdentity, "xml", "ivy", null);
        ivyDescriptorArtifact.setName("ivy");
        metadataArtifacts.add(ivyDescriptorArtifact);
    }

    @Override
    public void setModuleDescriptorGenerator(TaskProvider<? extends Task> descriptorGenerator) {
        moduleDescriptorGenerator = descriptorGenerator;
        if (gradleModuleDescriptorArtifact != null) {
            metadataArtifacts.remove(gradleModuleDescriptorArtifact);
        }
        gradleModuleDescriptorArtifact = null;
        updateModuleDescriptorArtifact();
    }

    private void updateModuleDescriptorArtifact() {
        if (!canPublishModuleMetadata()) {
            return;
        }
        if (moduleDescriptorGenerator == null) {
            return;
        }
        gradleModuleDescriptorArtifact = new SingleOutputTaskIvyArtifact(moduleDescriptorGenerator, publicationIdentity, "module", "json", null);
        metadataArtifacts.add(gradleModuleDescriptorArtifact);
        moduleDescriptorGenerator = null;
    }

    @Override
    public void descriptor(Action<? super IvyModuleDescriptorSpec> configure) {
        configure.execute(descriptor);
    }

    @Override
    public boolean isAlias() {
        return alias;
    }

    @Override
    public void setAlias(boolean alias) {
        this.alias = alias;
    }

    @Override
    public void from(SoftwareComponent component) {
        if (this.component != null) {
            throw new InvalidUserDataException(String.format("Ivy publication '%s' cannot include multiple components", name));
        }
        this.component = (SoftwareComponentInternal) component;
        artifactsOverridden = false;
        updateModuleDescriptorArtifact();
    }

    private void populateFromComponent() {
        if (populated) {
            return;
        }
        populated = true;
        if (component == null) {
            return;
        }
        PublicationWarningsCollector publicationWarningsCollector = new PublicationWarningsCollector(LOG, UNSUPPORTED_FEATURE, "");

        populateConfigurations();
        populateArtifacts();
        populateDependencies(publicationWarningsCollector);
        populateGlobalExcludes();
        warnForUnsupportedFeatures(publicationWarningsCollector);

        publicationWarningsCollector.complete(getDisplayName());
    }

    private void populateConfigurations() {
        for (UsageContext usageContext : component.getUsages()) {
            String conf = mapUsageToIvyConfiguration(usageContext);
            configurations.maybeCreate(conf);
            if (usageContext instanceof IvyPublishingAwareContext) {
                if (!((IvyPublishingAwareContext) usageContext).isOptional()) {
                    configurations.getByName("default").extend(conf);
                }
            } else {
                configurations.getByName("default").extend(conf);
            }
        }
    }

    private void populateArtifacts() {
        Set<PublishArtifact> seenArtifacts = Sets.newHashSet();
        for (UsageContext usageContext : component.getUsages()) {
            String conf = mapUsageToIvyConfiguration(usageContext);
            for (PublishArtifact publishArtifact : usageContext.getArtifacts()) {
                if (!artifactsOverridden && seenArtifacts.add(publishArtifact)) {
                    artifact(publishArtifact).setConf(conf);
                }
            }
        }
    }

    private void populateDependencies(PublicationWarningsCollector publicationWarningsCollector) {
        for (UsageContext usageContext : component.getUsages()) {
            for (ModuleDependency dependency : usageContext.getDependencies()) {
                String confMapping = confMappingFor(usageContext, dependency);
                if (!dependency.getAttributes().isEmpty()) {
                    publicationWarningsCollector.addUnsupported(String.format("%s:%s:%s declared with Gradle attributes", dependency.getGroup(), dependency.getName(), dependency.getVersion()));
                }
                if (dependency instanceof ProjectDependency) {
                    addProjectDependency((ProjectDependency) dependency, confMapping);
                } else {
                    ExternalDependency externalDependency = (ExternalDependency) dependency;
                    if (platformSupport.isTargettingPlatform(dependency)) {
                        publicationWarningsCollector.addUnsupported(String.format("%s:%s:%s declared as platform", dependency.getGroup(), dependency.getName(), dependency.getVersion()));
                    }
                    if (!versionMappingInUse && externalDependency.getVersion() == null) {
                        publicationWarningsCollector.addUnsupported(String.format("%s:%s declared without version", externalDependency.getGroup(), externalDependency.getName()));
                    }
                    addExternalDependency(externalDependency, confMapping, ((AttributeContainerInternal) usageContext.getAttributes()).asImmutable());
                }
            }
        }
    }

    private void populateGlobalExcludes() {
        for (UsageContext usageContext : component.getUsages()) {
            String conf = mapUsageToIvyConfiguration(usageContext);
            for (ExcludeRule excludeRule : usageContext.getGlobalExcludes()) {
                globalExcludes.add(new DefaultIvyExcludeRule(excludeRule, conf));
            }
        }
    }

    private String confMappingFor(UsageContext usageContext, ModuleDependency dependency) {
        String conf = mapUsageToIvyConfiguration(usageContext);

        String confMappingTarget = null;
        if (usageContext instanceof MavenPublishingAwareContext) {
            MavenPublishingAwareContext.ScopeMapping mapping = ((MavenPublishingAwareContext) usageContext).getScopeMapping();
            if (mapping == compile || mapping == runtime) {
                confMappingTarget = mapping.name();
            }
        }
        if (confMappingTarget == null) {
            confMappingTarget = dependency.getTargetConfiguration();
        }
        if (confMappingTarget == null) {
            confMappingTarget = Dependency.DEFAULT_CONFIGURATION;
        }

        return conf + "->" + confMappingTarget;
    }

    private void warnForUnsupportedFeatures(PublicationWarningsCollector publicationWarningsCollector) {
        for (UsageContext usageContext : component.getUsages()) {
            if (!usageContext.getDependencyConstraints().isEmpty()) {
                for (DependencyConstraint constraint : usageContext.getDependencyConstraints()) {
                    publicationWarningsCollector.addUnsupported(String.format("%s:%s:%s declared as a dependency constraint", constraint.getGroup(), constraint.getName(), constraint.getVersion()));
                }
            }
            if (!usageContext.getCapabilities().isEmpty()) {
                for (Capability capability : usageContext.getCapabilities()) {
                    publicationWarningsCollector.addUnsupported(String.format("Declares capability %s:%s:%s", capability.getGroup(), capability.getName(), capability.getVersion()));
                }
            }
        }
    }

    private String mapUsageToIvyConfiguration(UsageContext usageContext) {
        if (usageContext instanceof MavenPublishingAwareContext) {
            MavenPublishingAwareContext.ScopeMapping mapping = ((MavenPublishingAwareContext) usageContext).getScopeMapping();
            if (mapping == compile || mapping == runtime) {
                return mapping.name();
            }
        }
        return usageContext.getName();
    }

    private void addProjectDependency(ProjectDependency dependency, String confMapping) {
        ModuleVersionIdentifier identifier = projectDependencyResolver.resolve(ModuleVersionIdentifier.class, dependency);
        ivyDependencies.add(new DefaultIvyDependency(
                identifier.getGroup(), identifier.getName(), identifier.getVersion(), confMapping, dependency.isTransitive(), Collections.<DependencyArtifact>emptyList(), dependency.getExcludeRules()));
    }

    private void addExternalDependency(ExternalDependency dependency, String confMapping, ImmutableAttributes attributes) {
        ivyDependencies.add(new DefaultIvyDependency(dependency, confMapping, attributes));
    }

    @Override
    public void configurations(Action<? super IvyConfigurationContainer> config) {
        populateFromComponent();
        config.execute(configurations);
    }

    @Override
    public IvyConfigurationContainer getConfigurations() {
        populateFromComponent();
        return configurations;
    }

    @Override
    public IvyArtifact artifact(Object source) {
        return mainArtifacts.artifact(source);
    }

    @Override
    public IvyArtifact artifact(Object source, Action<? super IvyArtifact> config) {
        return mainArtifacts.artifact(source, config);
    }

    @Override
    public void setArtifacts(Iterable<?> sources) {
        artifactsOverridden = true;
        mainArtifacts.clear();
        for (Object source : sources) {
            artifact(source);
        }
    }

    @Override
    public DefaultIvyArtifactSet getArtifacts() {
        populateFromComponent();
        return mainArtifacts;
    }

    @Override
    public String getOrganisation() {
        return publicationIdentity.getOrganisation();
    }

    @Override
    public void setOrganisation(String organisation) {
        publicationIdentity.setOrganisation(organisation);
    }

    @Override
    public String getModule() {
        return publicationIdentity.getModule();
    }

    @Override
    public void setModule(String module) {
        publicationIdentity.setModule(module);
    }

    @Override
    public String getRevision() {
        return publicationIdentity.getRevision();
    }

    @Override
    public void setRevision(String revision) {
        publicationIdentity.setRevision(revision);
    }

    @Override
    public FileCollection getPublishableFiles() {
        populateFromComponent();
        return getPublishableArtifacts().getFiles();
    }

    @Override
    public PublicationArtifactSet<IvyArtifact> getPublishableArtifacts() {
        populateFromComponent();
        return publishableArtifacts;
    }

    @Override
    public void allPublishableArtifacts(Action<? super IvyArtifact> action) {
        publishableArtifacts.all(action);
    }

    @Override
    public void whenPublishableArtifactRemoved(Action<? super IvyArtifact> action) {
        publishableArtifacts.whenObjectRemoved(action);
    }

    @Override
    public IvyArtifact addDerivedArtifact(IvyArtifact originalArtifact, Factory<File> fileProvider) {
        IvyArtifact artifact = new DerivedIvyArtifact(originalArtifact, fileProvider);
        derivedArtifacts.add(artifact);
        return artifact;
    }

    @Override
    public void removeDerivedArtifact(IvyArtifact artifact) {
        derivedArtifacts.remove(artifact);
    }

    @Override
    public IvyPublicationIdentity getIdentity() {
        return publicationIdentity;
    }

    @Override
    public Set<IvyDependencyInternal> getDependencies() {
        populateFromComponent();
        return ivyDependencies;
    }

    @Override
    public IvyNormalizedPublication asNormalisedPublication() {
        populateFromComponent();
        DomainObjectSet<IvyArtifact> existingDerivedArtifacts = derivedArtifacts.matching(new Spec<IvyArtifact>() {
            @Override
            public boolean isSatisfiedBy(IvyArtifact artifact) {
                return artifact.getFile().exists();
            }
        });
        Set<IvyArtifact> artifactsToBePublished = CompositeDomainObjectSet.create(IvyArtifact.class, mainArtifacts, metadataArtifacts, existingDerivedArtifacts).matching(new Spec<IvyArtifact>() {
            @Override
            public boolean isSatisfiedBy(IvyArtifact element) {
                if (gradleModuleDescriptorArtifact == element) {
                    // We temporarily want to allow skipping the publication of Gradle module metadata
                    return gradleModuleDescriptorArtifact.isEnabled();
                }
                return true;
            }
        });
        return new IvyNormalizedPublication(name, getIdentity(), getIvyDescriptorFile(), artifactsToBePublished);
    }

    @Override
    public boolean writeGradleMetadataMarker() {
        if (canPublishModuleMetadata() && gradleModuleDescriptorArtifact != null && gradleModuleDescriptorArtifact.isEnabled()) {
            return true;
        }
        return false;
    }

    private boolean canPublishModuleMetadata() {
        // Cannot yet publish module metadata without component
        return getComponent() != null;
    }

    private File getIvyDescriptorFile() {
        if (ivyDescriptorArtifact == null) {
            throw new IllegalStateException("ivyDescriptorArtifact not set for publication");
        }
        return ivyDescriptorArtifact.getFile();
    }

    @Override
    public ModuleVersionIdentifier getCoordinates() {
        return DefaultModuleVersionIdentifier.newId(getOrganisation(), getModule(), getRevision());
    }

    @Nullable
    @Override
    public <T> T getCoordinates(Class<T> type) {
        if (type.isAssignableFrom(ModuleVersionIdentifier.class)) {
            return type.cast(getCoordinates());
        }
        return null;
    }

    @Override
    public ImmutableAttributes getAttributes() {
        return immutableAttributesFactory.of(ProjectInternal.STATUS_ATTRIBUTE, getDescriptor().getStatus());
    }

    private String getPublishedUrl(PublishArtifact source) {
        return getArtifactFileName(source.getClassifier(), source.getExtension());
    }

    private String getArtifactFileName(String classifier, String extension) {
        StringBuilder artifactPath = new StringBuilder();
        ModuleVersionIdentifier coordinates = getCoordinates();
        artifactPath.append(coordinates.getName());
        artifactPath.append('-');
        artifactPath.append(coordinates.getVersion());
        if (GUtil.isTrue(classifier)) {
            artifactPath.append('-');
            artifactPath.append(classifier);
        }
        if (GUtil.isTrue(extension)) {
            artifactPath.append('.');
            artifactPath.append(extension);
        }
        return artifactPath.toString();
    }

    @Override
    public PublishedFile getPublishedFile(PublishArtifact source) {
        final String publishedUrl = getPublishedUrl(source);
        return new PublishedFile() {
            @Override
            public String getName() {
                return publishedUrl;
            }

            @Override
            public String getUri() {
                return publishedUrl;
            }
        };
    }

    @Override
    public void versionMapping(Action<? super VersionMappingStrategy> configureAction) {
        this.versionMappingInUse = true;
        configureAction.execute(versionMappingStrategy);
    }

    @Override
    @Nullable
    public VersionMappingStrategyInternal getVersionMappingStrategy() {
        return versionMappingStrategy;
    }

    @Override
    public Set<IvyExcludeRule> getGlobalExcludes() {
        return globalExcludes;
    }
}
