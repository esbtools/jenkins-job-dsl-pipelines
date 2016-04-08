import cicd.pipelines.Pipeline
import cicd.pipelines.Component

bar = new Component(
  name: 'bar'
)

new Pipeline(
  project_name: 'foo',
  components: [bar],
  environments: ['dev','qa']
).build(this)
