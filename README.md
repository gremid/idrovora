# Idrovora - A pump station for your XProc pipelines

<img src="doc/idrovora.png"
     title="Idrovora by Marianna57 / Wikimedia Commons / CC-BY-SA-4.0"
     alt="Idrovora by Marianna57 / Wikimedia Commons / CC-BY-SA-4.0"
     align="right">

_This is work in progress!_

[XProc](https://www.w3.org/TR/xproc/) is a versatile language to describe
XML-based processing pipelines. With [XML Calabash](http://xmlcalabash.com/) and
its embedded [Saxon Processor](http://saxon.sourceforge.net/) exists a stable
and feature-rich implementation, that allows developers to implement document
processing logic in a platform-independent way using XML-based technologies.

Idrovora provides an execution context for XProc pipelines, where

1. pipelines are run with input read from and results written to the
   filesystem following a common directory layout,
1. the execution of pipelines can be triggered via requests to an embedded HTTP
   server or via filesystem events (_hot folders_),
1. pipelines are run concurrently and asynchronously by a daemon process, thereby
   not incurring JVM-related startup costs for each pipeline execution.

It is designed as a backend service for systems which depend on XML-based
processing logic and consequently have to incorporate a Java runtime environment
needed by the aforementioned tools, but want to do so with a minimalistic
interface based on HTTP and the local filesystem.

## Getting Started

### Prerequisites

In order to build, test and run Idrovora, you need to install

* [Java v8](https://jdk.java.net/)
* [Clojure v1.10](https://clojure.org/guides/getting_started)

Alternatively you can run Idrovora within a [Docker](https://www.docker.com/)
container in which case nothing is needed except a Docker installation.

### Build Docker container
    
```plaintext
$ docker build -t gremid/idrovora .
```

### Test

```plaintext
$ clojure -A:test -m cognitect.test-runner
```

### Develop and Run

```
$ clojure -m idrovora.cli --help
Idrovora - A pump station for your XProc pipelines
Copyright (C) 2020 Gregor Middell

See <https://github.com/gremid/idrovora> for more information.

Usage: clojure -m idrovora.cli [OPTION]...

Options:
  -x, --xpl-dir $IDROVORA_XPL_DIR           workspace/xpl   source directory with XProc pipeline definitions
  -j, --job-dir $IDROVORA_JOB_DIR           workspace/jobs  spool directory for pipeline jobs
  -p, --port $IDROVORA_HTTP_PORT            3000            HTTP port for embedded server
  -c, --cleanup $IDROVORA_CLEANUP_SCHEDULE  0 1 0 * * ?     Schedule for periodic cleanup of old jobs (cron expression)
  -a, --job-max-age $IDROVORA_JOB_MAX_AGE   PT168H          Maximum age of jobs; older jobs are removed periodically
  -h, --help
[…]
```

Starting Idrovora with defaults, creating a `workspace/` in the current
directory and running an HTTP server on port 3000, simply execute the JAR
without any arguments:

```
$ clojure -m idrovora.cli
2020-03-09 14:52:51 [main       | INFO  | idrovora.cli        ] Starting Idrovora
2020-03-09 14:52:51 [main       | INFO  | idrovora.http       ] Starting HTTP server at 3000/tcp
2020-03-09 14:52:51 [main       | INFO  | idrovora.workspace  ] Start watching 'workspace/jobs'
2020-03-09 14:52:51 [main       | INFO  | idrovora.workspace  ] Scheduling cleanup of old jobs (0 1 0 * * ?)
```

Pipelines can be defined in `workspace/xpl/`, for instance a pipeline named `test` in `workspace/xpl/test.xpl`:

```XProc
<?xml version="1.0" encoding="UTF-8"?>
<p:declare-step xmlns:p="http://www.w3.org/ns/xproc"
                xmlns:c="http://www.w3.org/ns/xproc-step"
                version="1.0">
  <p:option name="source-dir" required="true"/>
  <p:option name="result-dir" required="true"/>
  <p:load name="read-from-input">
    <p:with-option name="href" select="concat($source-dir,'document.xml')"/>
  </p:load>
  <p:identity/>
  <p:store name="store-to-output">
    <p:with-option name="href" select="concat($result-dir,'document.xml')"/>
  </p:store>
</p:declare-step>
```

Jobs for this pipeline can then be created in sub-directories under `workspace/jobs/test/`, i. e.

```
workspace/jobs/
└── test
    └── 82d11012-cf02-4ec0-b3c6-f9fe004de7b0
        ├── result
        │   └── document.xml
        ├── source
        │   └── document.xml
        └── status
            ├── job-failed
            ├── result-ready
            └── source-ready
```

In a job-directory, here one named with the UUID
`82d11012-cf02-4ec0-b3c6-f9fe004de7b0`, the sub-directories `source/` and
`result/` hold input and output data for the job; they are passed as URIs via
options (`source-dir` and `result-dir`) to the pipeline. Files in the `status/`
sub-directory are used for controlling job execution and signaling job
completion: 

* touching `status/source-ready` will signal to Idrovora that the sources have been written to  `source/` and the job can be scheduled for execution,
* upon creation/modification of `status/result-ready`, a listening process can
  assume that the pipeline executed successfully and results can be picked up
  from `result/`,
* the creation/modification of `status/job-failed` signals an error occurring
  while the job was run through the pipeline.


## Roadmap

* [x] XProc engine based on XML Calabash
  * [x] Call XProc pipelines with job context (source/result dirs) given as 
        pipeline options
  * [ ] Improved error reporting
* [x] Filesystem watcher, notifying engine when jobs are ready to be run
* [ ] HTTP interface
  * [x] Embedded HTTP server
  * [ ] Allow jobs to be triggered via POST requests
  * [ ] Synchronously respond to job trigger requests when jobs are completed
* [ ] User interface
  * [x] Basic command line interface
  * [x] Provide usage information/help
  * [x] Make HTTP port configurable
* [ ] Provide [runtime metrics](https://metrics.dropwizard.io/4.1.2/)
  
## Notes/Links

* [Piperack Docs](http://xmlcalabash.com/docs/reference/using-piperack.html)
* [Ruby FS Watcher Library](https://github.com/guard/listen)

## License

Copyright &copy; 2020 Gregor Middell.

This project is licensed under the GNU General Public License v3.0.
