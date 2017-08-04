import cicd.pipelines.Pipeline
import cicd.pipelines.Component

bar = new Component(
  name: 'bar',
  git_url: 'git://path.com/to/project',
  build: job('build.projectname') {
    jdk('java 8')
    logRotator {
      numToKeep(3)
    }
    steps {
      //whatever steps are needed for your build: eg. maven, gradle, shell
    }
  },
  analyze: job('analyze') {
    //job details
  }
)

new Pipeline(
  project_name: 'foo',
  components: [bar],
  environments: ['dev','qa'],
  prepare_release: job('prepare_release') {
    //job details
  },
  deploy: [
    dev: job("deploy.dev") {
      //job details
    },
    qa: job("deploy.qa") {
      //job details
    }
  ]
).build(this)
