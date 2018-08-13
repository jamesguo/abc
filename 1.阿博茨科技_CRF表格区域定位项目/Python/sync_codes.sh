#!/usr/bin/env bash

rsync -arv --exclude=logs --exclude=.git ./ jhqiu@10.11.255.151:~/virenv/python2/crf-table-test