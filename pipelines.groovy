package cicd

import groovy.transform.Field

@Field static def CONFIG_DEFAULTS = [
    root_folder_name: 'cicd',
    git_branch: 'master',
    manual_promotion_environments: ['stage', 'prod'],
]

static def create_pipeline(context) {
    context.with {
        def pipeline_config = context.binding.variables.withDefault { key -> CONFIG_DEFAULTS.get(key) }

        def root_folder = folder(pipeline_config.root_folder_name)
        def project_folder = folder("${root_folder.name}/${pipeline_config.project_name}")
        deliveryPipelineView("${pipeline_config.project_name} Pipeline") {
            enableManualTriggers()
            showChangeLog()
            pipelines {
                pipeline_config.components.each { component_name, config ->
                    component(component_name, "${pipeline_config.root_folder_name}/${pipeline_config.project_name}/${component_name}/build")
                }
            }
        }

        def prepare_deploy_job = pipeline_config.prepare_deploy ?: job('prepare_deploy')
        prepare_deploy_job.with {
            name = "${project_folder.name}/prepare_deploy"
            deliveryPipelineConfiguration('Build', 'Prepare Deployment')
            parameters {
                stringParam('build_job_build_number', null, 'Number from successful build.')
                stringParam('commit', pipeline_config.git_branch, 'Commit to deploy.')
                stringParam('previous_commit', null, 'Reference of previous commit. Used to calculate deployment delta.')
            }
        }

        def create_deploy_job = { environment ->
            def deploy_job = pipeline_config.deploy[environment] ?: job("deploy.${environment}")
            deploy_job.with {
                name = "${project_folder.name}/deploy.${environment}"
                deliveryPipelineConfiguration(environment, 'Deploy')
                parameters {
                    stringParam('build_job_build_number', null, 'Number from successful build.')
                    stringParam('commit', pipeline_config.git_branch, 'Commit to deploy.')
                    stringParam('previous_commit', null, 'Reference of previous commit. Used to calculate deployment delta.')
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
            def test_job = pipeline_config.test?.get(environment) ?: job("test.${environment}")
            test_job.with {
                name = "${project_folder.name}/test.${environment}"
                deliveryPipelineConfiguration(environment, 'Test')
                parameters {
                    stringParam('build_job_build_number', null, 'Number from successful build.')
                    stringParam('commit', pipeline_config.git_branch, 'Commit to deploy.')
                    stringParam('previous_commit', null, 'Reference of previous commit. Used to calculate deployment delta.')
                }
                publishers {
                    downstream_job = "${project_folder.name}/promote_from.${environment}"
                    if (next_environment in pipeline_config.manual_promotion_environments) {
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
            def promote_job = pipeline_config.promote_jobs?.get(environment) ?: job("promote_from.${environment}")
            promote_job.with {
                name = "${project_folder.name}/promote_from.${environment}"
                deliveryPipelineConfiguration(environment, 'Promote')
                parameters {
                    stringParam('build_job_build_number', null, 'Number from successful build.')
                    stringParam('commit', pipeline_config.git_branch, 'Commit to deploy.')
                    stringParam('previous_commit', null, 'Reference of previous commit. Used to calculate deployment delta.')
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

        pipeline_config.environments.eachWithIndex { environment, index ->
            next_environment = null
            if (index != pipeline_config.environments.size - 1) {
                next_environment = pipeline_config.environments[index + 1]
            }
            create_deploy_job(environment)
            create_test_job(environment)
            create_promote_job(environment, next_environment)
        }

        pipeline_config.components.each { component, component_config ->
            def config = (pipeline_config + component_config).withDefault { key -> CONFIG_DEFAULTS.get(key) }
            component_folder = folder("${project_folder.name}/${component}")

            def build_job = config.build ?: job("build.${config.project_name}.${component}")
            build_job.with {
                deliveryPipelineConfiguration('Build', 'Actual Build')
                parameters {
                    stringParam('commit', config.git_branch, 'Commit/Branch/Tag to build.')
                }
                scm {
                    git(config.git_url, config.git_branch)
                }
            }

            job("${component_folder.name}/build") {
                deliveryPipelineConfiguration('Build', 'C-I Build')
                scm {
                    git(config.git_url, config.git_branch)
                }
                steps {
                    downstreamParameterized {
                        trigger(build_job.name) {
                            block {
                                buildStepFailure('FAILURE')
                                failure('FAILURE')
                                unstable('UNSTABLE')
                            }
                            parameters {
                                predefinedProp('commit', '${GIT_COMMIT}')
                            }
                        }
                    }
                }
                triggers {
                    scm('H/2 * * * *')
                }
                publishers {
                    downstreamParameterized {
                        trigger("${component_folder.name}/integration_test") {
                            condition('SUCCESS')
                            parameters {
                                predefinedProp('commit', '${GIT_COMMIT}')
                                predefinedProp('build_job_build_number', "\${TRIGGERED_BUILD_NUMBER_build_${config.project_name}")
                                predefinedProp('previous_commit', '${GIT_PREVIOUS_SUCCESSFUL_COMMIT}')
                            }
                        }
                        trigger("${component_folder.name}/analyze") {
                            condition('UNSTABLE_OR_BETTER')
                            parameters {
                                predefinedProp('commit', '${GIT_COMMIT}')
                                predefinedProp('build_job_build_number', "\${TRIGGERED_BUILD_NUMBER_build_${config.project_name}")
                                predefinedProp('previous_commit', '${GIT_PREVIOUS_SUCCESSFUL_COMMIT}')
                            }
                        }
                    }
                }
            }

            def integration_test_job = config.integration_test ?: job('integration_test')
            integration_test_job.with {
                name = "${component_folder.name}/integration_test"
                deliveryPipelineConfiguration('Build', 'Integration Test')
                parameters {
                    stringParam('build_job_build_number', null, 'Build number of build that triggered analysis.')
                    stringParam('commit', config.git_branch, 'Commit to analyze.')
                    stringParam('previous_commit', null, 'Reference of previous successful build.')
                }
                publishers {
                    downstreamParameterized {
                        trigger("${project_folder.name}/deploy.${config.environments[0]}") {
                            condition('SUCCESS')
                            parameters {
                                currentBuild()
                            }
                        }
                    }
                }
            }

            def analyze_job = config.analyze ?: job('analyze')
            analyze_job.with {
                name = "${component_folder.name}/analyze"
                deliveryPipelineConfiguration('Build', 'Analyze')
                parameters {
                    stringParam('build_job_build_number', null, 'Build number of build that triggered analysis.')
                    stringParam('commit', config.git_branch, 'Commit to analyze.')
                    stringParam('previous_commit', null, 'Reference of previous successful build.')
                }
            }
        }
    }
}
