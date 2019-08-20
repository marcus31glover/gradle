/*
 * Copyright 2019 the original author or authors.
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
package org.gradle.integtests.resolve.compatibility

import org.gradle.integtests.fixtures.RepoScriptBlockUtil
import org.gradle.integtests.resolve.AbstractModuleDependencyResolveTest

class ArtifactAndClassifierCompatibilityIntegrationTest extends AbstractModuleDependencyResolveTest {

    def "resolves a dependency with classifier"() {
        given:
        repository {
            'org:foo:1.0' {
                dependsOn(group: 'org', artifact:'bar', version:'1.0', classifier:'classy')
            }
            'org:bar:1.0' {
                withModule {
                    artifact(type: 'jar', classifier: 'classy')
                }
                withoutGradleMetadata()
            }
        }

        and:
        buildFile << """
            dependencies {
                conf 'org:foo:1.0'
            }
        """

        when:
        repositoryInteractions {
            'org:foo:1.0' {
                expectResolve()
            }
            'org:bar:1.0' {
                expectGetMetadata()
                expectGetArtifact(classifier: 'classy')
            }
        }
        succeeds "checkDep"

        then:
        resolve.expectGraph {
            root(':', ':test:') {
                module("org:foo:1.0") {
                    module("org:bar:1.0") {
                        artifact(classifier: 'classy')
                    }
                }
            }
        }
    }

    def "existing oss library use case"() {
        given:
        repository {
            'org:mylib:1.0' {
                // https://repo1.maven.org/maven2/com/conversantmedia/disruptor/1.2.15/
                dependsOn(group: 'com.conversantmedia', artifact: 'disruptor', version: '1.2.15', classifier: 'jdk10')
            }
        }

        and:
        buildFile.text = """
            repositories {
                ${RepoScriptBlockUtil.jcenterRepositoryDefinition()}
            }
        """ + buildFile.text

        buildFile << """
            dependencies {
                conf 'org:mylib:1.0'
            }
        """

        when:
        repositoryInteractions {
            'org:mylib:1.0' {
                expectResolve()
            }
        }
        succeeds "checkDep"

        then:
        resolve.expectGraph {
            root(':', ':test:') {
                module("org:mylib:1.0") {
                    module("com.conversantmedia:disruptor:1.2.15") {
                        artifact(classifier: 'jdk10')
                        module("org.slf4j:slf4j-api:1.7.13")
                    }
                }
            }
        }
    }
}
