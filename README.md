# Jenkins Job DSL Pipelines Framework

## Definition

The *Jenkins CI/CD Pipline* is a wrapper around the Job DSL and Pipeline Jenkins plugins. The goal is to be able to tie the two together in order to be able to define in code how a project is built and promoted through environments.

There are essentially two stages. The first is the project build stage and the second is the promotion of artifacts to each environment. Both stages are a collection of jobs. This table explains what jobs are in each stage.

| Stage          | Job              | Description
| -------------- | ---------------- | -----------
| Build          | C-I Build        | Master job that tracks the c-i branch of a component.
|                | Actual Build     | Job that actually performs the build. Used as a "subproject" of C-I Build.
|                | Analyze          | Performs static analysis of the code. Not used to gate deployment, just used to inform human interaction with the pipeline.
|                | Prepare Release  | Prepares a release.
|                | Integration Test | Run any integration tests for the component.
| ${environment} | Deploy           | Deploy the code to ${environment}.
|                | Test             | Run smoke tests and automated tests to verify the state of ${environment}.
|                | Promote          | Promote to the next environment.

In *Jenkins CI/CD Pipline* speak, the *Build* stage is encapsulated in a *Component* and the *${environment}* stages are handled within the *Pipeline* itself.

## Required Jenkins Plugins
- build-pipeline-plugin
- cloudbees-folder
- delivery-pipeline-plugin
- git
- git-client
- instant-messaging
- ircbot
- job-dsl
- jquery
- multiple-scms
- parameterized-trigger
- saml
- scm-api
- token-macro

# License
AGPLv3; see LICENSE.txt for details
