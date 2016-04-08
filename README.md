Jenkins Job DSL Pipelines Framework
===================================

Requirements
------------
The following Jenkins plugins:

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

Usage
-----
See `example.groovy` for a minimal example. You'll need to set up the seed job
to have the framework referenceable by `import cicd.pipelines.Component`, etc.

License
-------
AGPLv3; see LICENSE.txt for details
