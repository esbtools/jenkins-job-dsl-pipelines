package cicd.pipelines

import javaposse.jobdsl.dsl.Job
import javaposse.jobdsl.dsl.Folder

class Component {
    /** Name of the component */
    String name

    /** Git URL */
    String git_url

    /** Git branch */
    String git_branch = 'master'

    /** Job to build the component */
    Job build

    /** Job to analyze the component */
    Job analyze

    /** Job to predeploy test the component */
    Job predeploy_test

    /** Names of jobs to use as upstreams */
    List<String> upstreams

    void configure(def dslFactory, String project_name, Folder project_folder) {
        if (dslFactory == null) {
            throw new IllegalArgumentException('Must specify dslFactory')
        }
        if (project_name == null) {
            throw new IllegalArgumentException('Must specify project_name')
        }
        if (project_folder == null) {
            throw new IllegalArgumentException('Must specify project_folder')
        }
        String component_name = name
        dslFactory.with {
            def component_folder = folder("${project_folder.name}/Build/${name}")

            def build_job = build ?: job("build.${project_name}.${name}")
            build_job.with {
                deliveryPipelineConfiguration('Build', 'Actual Build')
                parameters {
                    stringParam('commit', git_branch, 'Commit/Branch/Tag to build.')
                }
                scm {
                    git(git_url, git_branch)
                }
            }

            job("${component_folder.name}/build") {
                deliveryPipelineConfiguration('Build', 'C-I Build')
                scm {
                    git(git_url, git_branch)
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
                                currentBuild()
                            }
                        }
                    }
                }
                triggers {
                    scm('H/2 * * * *')
                    upstreams?.each { upstream_job ->
                        upstream(upstream_job)
                    }
                }
                publishers {
                    downstreamParameterized {
                        trigger("${component_folder.name}/predeploy_test") {
                            condition('SUCCESS')
                            parameters {
                                predefinedProp('commit', '${GIT_COMMIT}')
                                predefinedProp('build_job_build_number', "\${TRIGGERED_BUILD_NUMBER_${build_job.name.replaceAll('[^a-zA-Z0-9]+', '_')}}")
                                predefinedProp('previous_commit', '${GIT_PREVIOUS_SUCCESSFUL_COMMIT}')
                            }
                        }
                        trigger("${component_folder.name}/analyze") {
                            condition('UNSTABLE_OR_BETTER')
                            parameters {
                                predefinedProp('commit', '${GIT_COMMIT}')
                                predefinedProp('build_job_build_number', "\${TRIGGERED_BUILD_NUMBER_${build_job.name.replaceAll('[^a-zA-Z0-9]+', '_')}}")
                                predefinedProp('previous_commit', '${GIT_PREVIOUS_SUCCESSFUL_COMMIT}')
                            }
                        }
                    }
                }
            }

            def predeploy_test_job = predeploy_test ?: job('predeploy_test')
            predeploy_test_job.with {
                name = "${component_folder.name}/predeploy_test"
                deliveryPipelineConfiguration('Build', 'Predeploy Test')
                parameters {
                    stringParam('build_job_build_number', null, 'Build number of build that triggered predeploy test.')
                    stringParam('commit', git_branch, 'Commit to analyze.')
                    stringParam('previous_commit', null, 'Reference of previous successful build.')
                }
                publishers {
                    downstreamParameterized {
                        trigger("${project_folder.name}/Build/prepare_release") {
                            condition('SUCCESS')
                            parameters {
                                predefinedProp("${component_name}_previous_commit", '${previous_commit}')
                                predefinedProp("${component_name}_commit", '${commit}')
                                predefinedProp("${component_name}_build_number", '${build_job_build_number}')
                            }
                        }
                    }
                }
            }

            def analyze_job = analyze ?: job('analyze')
            analyze_job.with {
                name = "${component_folder.name}/analyze"
                deliveryPipelineConfiguration('Build', 'Analyze')
                parameters {
                    stringParam('build_job_build_number', null, 'Build number of build that triggered analysis.')
                    stringParam('commit', git_branch, 'Commit to analyze.')
                    stringParam('previous_commit', null, 'Reference of previous successful build.')
                }
                scm {
                    git(git_url, '${commit}')
                }
            }
        }
    }
}
