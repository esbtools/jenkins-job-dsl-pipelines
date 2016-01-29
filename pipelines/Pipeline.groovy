package cicd.pipelines

import javaposse.jobdsl.dsl.Job
import javaposse.jobdsl.dsl.Folder

class Pipeline {
    /** Used with `with` to invoke Job DSL methods */
    def dslFactory

    /** List of components to have as part of the pipeline */
    List<Component> components

    /** Name of the root folder to store associated jobs */
    String root_folder_name = 'cicd'

    /** Name of the project */
    String project_name = null

    /** Job used to prepare a release */
    Job prepare_release

    /** List of environments to target, in-order */
    List<String> environments = []

    /** List of environments to use manual promotion with */
    List<String> manual_promotion_environments = ['stage', 'prod']

    /** Deploy jobs for each environment */
    Map<String,Job> deploy = [:]

    /** Test jobs for each environment */
    Map<String,Job> test = [:]

    /** Promote jobs for each environment */
    Map<String,Job> promote = [:]

    void build() {
        if (dslFactory == null) {
            throw new IllegalArgumentException('Must specify dslFactory')
        }
        if (project_name == null) {
            throw new IllegalArgumentException('Must specify project_name')
        }
        dslFactory.with {
            def root_folder = folder(root_folder_name)
            def project_folder = folder("${root_folder.name}/${project_name}")
            deliveryPipelineView("${project_name} Pipeline") {
                enableManualTriggers()
                showChangeLog()
                pipelines {
                    components.each { component ->
                        component(component.name, "${project_folder.name}/build/${component.name}/build")
                    }
                }
            }

            components.each {
                it.configure(dslFactory, project_name, project_folder)
            }

            def prepare_release_job = prepare_release ?: job('prepare_release (noop)')
            prepare_release_job.with {
                name = "${project_folder.name}/prepare_release"
                deliveryPipelineConfiguration('Build', 'Prepare Release')
                parameters {
                    stringParam('release_identifier', "ci-${project_name.replace(' ', '_')}-\${BUILD_NUMBER}")
                    components.each { component ->
                        stringParam("${component.name}_previous_commit", null, "Previous successful commit for ${component.name}.")
                        stringParam("${component.name}_commit", component.git_branch, "Current commit for ${component.name}.")
                        stringParam("${component.name}_build_number", null, "Build number for ${component.name}.")
                    }
                }
                publishers {
                    downstreamParameterized {
                        trigger("${project_folder.name}/deploy.${environments[0]}") {
                            condition('SUCCESS')
                            parameters {
                                predefinedProp("release_identifier", '${release_identifier}')
                            }
                        }
                    }
                }
            }

            def create_deploy_job = { environment ->
                def deploy_job = deploy[environment] ?: job("deploy.${environment}")
                deploy_job.with {
                    name = "${project_folder.name}/${environment}/deploy"
                    deliveryPipelineConfiguration(environment, 'Deploy')
                    parameters {
                        stringParam('release_identifier', null, 'Identifier of release to deploy.')
                    }
                    publishers {
                        downstreamParameterized {
                            trigger("${project_folder.name}/test.${environment}") {
                                condition('SUCCESS')
                                parameters {
                                    currentBuild()
                                }
                            }
                        }
                    }
                }
            }

            create_test_job = { environment ->
                def test_job = test?.get(environment) ?: job("test.${environment}")
                test_job.with {
                    name = "${project_folder.name}/${environment}/test"
                    deliveryPipelineConfiguration(environment, 'Test')
                    parameters {
                        stringParam('release_identifier', null, 'Release identifier. Can be used in reports.')
                    }
                    publishers {
                        downstream_job = "${project_folder.name}/promote_from.${environment}"
                        if (next_environment in manual_promotion_environments) {
                            buildPipelineTrigger(downstream_job) {
                                parameters {
                                    currentBuild()
                                }
                            }
                        }
                        else {
                            downstreamParameterized {
                                trigger(downstream_job) {
                                    condition('SUCCESS')
                                    parameters {
                                        currentBuild()
                                    }
                                }
                            }
                        }
                    }
                }
            }

            create_promote_job = { environment, next_environment ->
                def promote_job = promote.get(environment) ?: job("promote_from.${environment}")
                promote_job.with {
                    name = "${project_folder.name}/${environment}/promote_from"
                    deliveryPipelineConfiguration(environment, 'Promote')
                    parameters {
                        stringParam('release_identifier', null, 'Identifier of the release to promote.')
                    }
                    if (next_environment != null) {
                        publishers {
                            downstreamParameterized {
                                trigger("${project_folder.name}/deploy.${next_environment}") {
                                    condition('SUCCESS')
                                    parameters {
                                        currentBuild()
                                    }
                                }
                            }
                        }
                    }
                }
            }

            environments.eachWithIndex { environment, index ->
                next_environment = null
                if (index != environments.size - 1) {
                    next_environment = environments[index + 1]
                }
                create_deploy_job(environment)
                create_test_job(environment)
                create_promote_job(environment, next_environment)
            }
        }
    }
}
