[IBM MQ](http://www.ibm.com/software/products/en/ibm-mq) is messaging middleware
that can be used to integrate applications and business data across multiple platforms.
[Lagom](https://www.lagomframework.com/) applications can conveniently integrate with
IBM MQ with the [Alpakka JMS connector](http://developer.lightbend.com/docs/alpakka/current/jms.html).

This example project implements a simple "hello, world" service that can store
custom per-user greetings. Changes to greetings are sent to a remote IBM MQ message
queue and received by a single listener running in the Lagom cluster
[Akka Cluster Singleton](http://doc.akka.io/docs/akka/current/scala/cluster-singleton.html). 

Greetings are accessed and changed over HTTP using
a pair of [Lagom Services](https://www.lagomframework.com/documentation/1.4.x/scala/ServiceDescriptors.html).

Greetings are read from a Lagom Persistent Entity. Greetings are written to the
same entity, but we do this via IBM MQ in order to demonstrate usage.
When a request to change a greeting arrives we create a message and send it via
MQ to a remote message queue. This message will be received by another part of our
application, which processes messages and which runs as an a [Akka Cluster Singleton](http://doc.akka.io/docs/akka/current/scala/cluster-singleton.html).
This singleton routes the update to the Lagom Persistent Entity.

## Table of Contents

1.  [Prerequisites](#prerequisites)
2.  [Set up Db2](#set-up-db2)
3.  [Create the `HELLO` database](#create-the-hello-database)
3.  [Download and set up the Lagom service](#download-and-set-up-the-lagom-service)
4.  [Download and install the Db2 JDBC driver](#download-and-install-the-db2-jdbc-driver)
5.  [Start the Lagom service](#start-the-lagom-service)
6.  [Test the Lagom service](#test-the-lagom-service)
7.  [Stop the Lagom service](#stop-the-lagom-service)
8.  [Next steps](#next-steps)

## Prerequisites

To build and run this example, you need to have the following installed:

- [git](https://git-scm.com/)
- [Java SE 8 JDK](http://www.oracle.com/technetwork/java/javase/overview/index.html)
- [Maven 3.2.1+](https://maven.apache.org/) to build and run the Lagom project (3.5.0 recommended)
- Docker XXXXXX needs URL

## Start IBM MQ Docker image

IBM provides a Docker image that runs MQ and sets up a few demonstration queues.
Our example application will send messages to these demonstration queues.

To start a docker container based on this image, run:

```
$ docker run --env LICENSE=accept --env MQ_QMGR_NAME=QM1 --publish 1414:1414 --publish 9443:9443 ibmcom/mq:9
```

Note that the `--env LICENSE=accept` argument indicates that you
[accept the Docker image licenses](https://github.com/ibm-messaging/mq-docker#usage).
 
Once the container is running you'll see a message like:

```
IBM MQ Queue Manager QM1 is now fully running
```

You can view the IBM MQ administration interface at [https://localhost:9443].

Docker will continue to run the container until you terminate the process, for example by
typing _Control-C_ in the terminal.

## Download and set up the Lagom service

Follow these steps to get a local copy of this project. You can supply the credentials in a configuration file or as environment variables.

1.  Open a command line shell and clone this repository:
    ```
    git clone https://github.com/lagom/ibm-integration-examples.git
    ```
2.  Change into the root directory for this example:
    ```
    cd lagom-mq-example
    ```

## Download and install the MQ client driver

To build and run the Lagom service, you will need to make the MQ client library available to Maven:

- If your organization has an internal Maven artifact repository that already hosts the Db2 JDBC driver, you can use this.
  You might need to change the `groupId`, `artifactId`, and `version` for the dependency in this project's top-level
  `pom.xml` file to match the values used in your repository.

- Otherwise, download the [Db2 JDBC Driver](http://www-01.ibm.com/support/docview.wss?uid=swg21363866) (`db2jcc4.jar`) version 4.23.42 from IBM to your current directory. Then, run the following command to install it to your local Maven repository:

  1. Go to [IBM's download page for MQ 9.0.0.1](http://ibm.biz/mq9001redistclients).
  
  2. Click on the link _9.0.0.1-IBM-MQC-Redist-Java_.

  3. You may be asked to log in to IBM. If needed, you can create a free IBMid by following the link to _Create an IBMId_.
  
  4. Agree to the license agreement.
  
  5. Click on the link _9.0.0.1-IBM-MQC-Redist-Java.zip (20.8 MB)_.
  
  6. Extract the MQ client JAR files.

     ```
     $ jar xf 9.0.0.1-IBM-MQC-Redist-Java.zip java/lib/{com.ibm.mq.allclient.jar,jms.jar}
     $ mvn install:install-file -Dfile=java/lib/com.ibm.mq.allclient.jar -Dversion=9.0.0.1 -DgroupId=com.ibm.mq -DartifactId=mq-allclient.jar -Dpackaging=jar
     $ mvn install:install-file -Dfile=java/lib/jms.jar -Dversion=2.0 -DgroupId=com.ibm.mq -DartifactId=jms-api.jar -Dpackaging=jar
     ```

1. Install Docker.
2. In one terminal, run IBM's demonstration MQ Docker image:

3. In a second terminal, use sbt to run the this application.
   ```
   $ sbt runAll
   ```
4. In a third terminal, access the application using curl or your web browser:
   ```
   $ curl http://localhost:9000/api/hello/World
   Hello, World!
   $ curl -H "Content-Type: application/json" -X POST -d '{"message":"Hola"}' http://localhost:9000/api/hello/World
   $ curl http://localhost:9000/api/hello/World
   Hola, World!
   ```