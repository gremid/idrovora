#!/usr/bin/env python3

from pathlib import Path
from shutil import rmtree
from subprocess import run

dist_dir = (Path(__file__) / '..').resolve()
project_dir = (Path(dist_dir) / '..').resolve()
classes_dir = (Path(project_dir) / 'classes').resolve()

if classes_dir.is_dir():
    rmtree(classes_dir.as_posix())
classes_dir.mkdir()

run([
    'clojure', '-e',
    '(doseq [p [\'idrovora.cli \'clojure.tools.logging.impl]] (compile p))'
], cwd=project_dir.as_posix(), check=True)

run([
    'clojure', '-m', 'uberdeps.uberjar',
    '--level', 'error',
    '--deps-file', '../deps.edn',
    '--target', 'idrovora.jar',
    '--main-class', 'idrovora.cli'
], cwd=dist_dir.as_posix(), check=True)

rmtree(classes_dir.as_posix())
