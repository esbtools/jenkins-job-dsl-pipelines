package cicd.pipelines

import javaposse.jobdsl.dsl.Job
import javaposse.jobdsl.dsl.Folder

class Pipeline {
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

    /** What email to use for notifications */
    String notification_email = null

    /** Which channel to notify in */
    String notification_irc_channel = null

    void build(def dslFactory) {
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
                    this.components.each { pipeline_component ->
                        component(pipeline_component.name, "${project_folder.name}/Build/${pipeline_component.name}/build")
                    }
                }
            }
            folder("${root_folder.name}/${project_name}/Build")

            components.each {
                it.configure(dslFactory, project_name, project_folder)
            }

            def prepare_release_job = prepare_release ?: job('prepare_release')
            prepare_release_job.with {
                name = "${project_folder.name}/Build/prepare_release"
                deliveryPipelineConfiguration('Build', 'Prepare Release')
                if (prepare_release == null) {
                    deliveryPipelineConfiguration('Build', 'Prepare Release (placeholder)')
                }
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
                        trigger("${project_folder.name}/${environments[0]}/deploy") {
                            condition('SUCCESS')
                            parameters {
                                predefinedProp("release_identifier", '${release_identifier}')
                            }
                        }
                    }
                    if (notification_email) {
                        mailer(notification_email, true)
                    }
                    if (notification_irc_channel) {
                        irc {
                            channel(notification_irc_channel)
                            notificationMessage('SummaryOnly')
                            strategy('ALL')
                        }
                    }
                }
            }

            def create_deploy_job = { environment ->
                def deploy_job = deploy[environment] ?: job("deploy.${environment}")
                deploy_job.with {
                    name = "${project_folder.name}/${environment}/deploy"
                    deliveryPipelineConfiguration(environment, 'Deploy')
                    if (deploy[environment] == null) {
                        deliveryPipelineConfiguration(environment, 'Deploy (placeholder)')
                    }
                    parameters {
                        stringParam('release_identifier', null, 'Identifier of release to deploy.')
                    }
                    publishers {
                        downstreamParameterized {
                            trigger("${project_folder.name}/${environment}/test") {
                                condition('SUCCESS')
                                parameters {
                                    currentBuild()
                                }
                            }
                        }
                        if (notification_email) {
                            mailer(notification_email, true)
                        }
                        if (notification_irc_channel) {
                            irc {
                                channel(notification_irc_channel)
                                notificationMessage('SummaryOnly')
                                strategy('ALL')
                            }
                        }
                    }
                }
            }

            create_test_job = { environment ->
                def test_job = test[environment] ?: job("test.${environment}")
                test_job.with {
                    name = "${project_folder.name}/${environment}/test"
                    deliveryPipelineConfiguration(environment, 'Test')
                    if (test[environment] == null) {
                        deliveryPipelineConfiguration(environment, 'Test (placeholder)')
                    }
                    parameters {
                        stringParam('release_identifier', null, 'Release identifier. Can be used in reports.')
                    }
                    publishers {
                        downstream_job = "${project_folder.name}/${environment}/promote_from"
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
                def promote_job = promote[environment] ?: job("promote_from.${environment}")
                promote_job.with {
                    name = "${project_folder.name}/${environment}/promote_from"
                    deliveryPipelineConfiguration(environment, 'Promote')
                    if (promote[environment] == null) {
                        deliveryPipelineConfiguration(environment, 'Promote (placeholder)')
                    }
                    parameters {
                        stringParam('release_identifier', null, 'Identifier of the release to promote.')
                    }
                    if (next_environment != null) {
                        publishers {
                            downstreamParameterized {
                                trigger("${project_folder.name}/${next_environment}/deploy") {
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
                folder("${project_folder.name}/${environment}")
                create_deploy_job(environment)
                create_test_job(environment)
                create_promote_job(environment, next_environment)
            }
        }
    }
}
