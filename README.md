# jenkins-s2i
This repo contains a sample s2i for jenkins used by the Red Hat Open Innovation Labs.

## Adding plugins
Add plugins to the `plugins.txt` file by including them in the form of `<plugin-id>:<version>`. Eg `email-ext:2.11`.

To get a list of all plugins and their version on an existing Jenkins in this format run this command in the Groovy console.

```groovy
Jenkins.instance.pluginManager.plugins.each{
  plugin ->
    println ("${plugin.getShortName()}:${plugin.getVersion()}")
}
```
## Adding seed jobs
The JENKINS created by this s2i is loaded with a seed for creating a react app and golang pipeline. Additional seeds can be created by including them in `configuration/jobs/`. Create a new folder (for the jenkins job name) and add a `config.xml`. The example seeds point to a DSL stored with sample apps.
