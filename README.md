# Idrovora - A pump station for your XProc pipelines

<img src="doc/idrovora.png"
     title="Idrovora by Marianna57 / Wikimedia Commons / CC-BY-SA-4.0"
     alt="Idrovora by Marianna57 / Wikimedia Commons / CC-BY-SA-4.0"
     align="right">

## Getting Started

These instructions will get you a copy of the project up and running on your
local machine for development and testing purposes. See deployment for notes on
how to deploy the project on a live system.

### Prerequisites

In order to build Idrovora, you need to install

* [Clojure v1.10](https://clojure.org/guides/getting_started)
* [Python v3](https://www.python.org/)

For running Idrovora, only a Java runtime environment is needed, version 8 or later will do.

* [Java](https://jdk.java.net/)

### Build

Compiling and packaging Idrovora in a JAR including all dependencies can be achieved via a Python-based build script:

```
$ python3 dist/package.py
```

When successfully run, you should find the resulting JAR file in `dist/`.

### Run

The built JAR file can be executed. In order to get help on the available options, execute the following:

```
$ java -jar dist/idrovora.jar --help
Idrovora - A pump station for your XProc pipelines
Copyright (C) 2020 Gregor Middell

See <https://github.com/gremid/idrovora> for more information.

Usage: java -jar idrovora.jar [OPTION]...

Options:
  -x, --xpl-dir $IDROVORA_XPL_DIR           workspace/xpl   source directory with XProc pipeline definitions
  -j, --job-dir $IDROVORA_JOB_DIR           workspace/jobs  spool directory for pipeline jobs
  -p, --port $IDROVORA_HTTP_PORT            3000            HTTP port for embedded server
  -c, --cleanup $IDROVORA_CLEANUP_SCHEDULE  0 1 0 * * ?     Schedule for periodic cleanup of old jobs (cron expression)
  -a, --job-max-age $IDROVORA_JOB_MAX_AGE   PT168H          Maximum age of jobs; older jobs are removed periodically
  -h, --help
[…]
```

## Roadmap

* [x] XProc engine based on XML Calabash
  * [x] Call XProc pipelines with job context (source/result dirs) given as
        pipeline options
    [ ] Improved error reporting
* [x] Filesystem watcher, notifying engine when jobs are ready to be run
* [ ] HTTP interface
  * [x] Embedded HTTP server
  * [ ] Allow jobs to be triggered via POST requests
  * [ ] Synchronously respond to job trigger requests when jobs are completed
* [ ] User interface
  * [x] Basic command line interface
  * [ ] Provide usage information/help
  * [ ] Make HTTP port configurable
* [ ] Provide [runtime metrics](https://metrics.dropwizard.io/4.1.2/)
  
## Notes/Links

* [Piperack Docs](http://xmlcalabash.com/docs/reference/using-piperack.html)
* [Ruby FS Watcher Library](https://github.com/guard/listen)

## License

Copyright &copy; 2020 Gregor Middell.

This project is licensed under the GNU General Public License v3.0.
