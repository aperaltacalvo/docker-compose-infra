## Jenkins Docker Container

Usage:
```
$ docker build -t jenkins .
$ docker run -d -p=8080:8080 jenkins
```

Once Jenkins is up and running go to http://localhost:8080

## Update Plugins

Install and update all plugins via the Jenkins Plugin Manager.
* http://<jenkins-url:port>/pluginManager/

