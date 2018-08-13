#!/usr/bin/env bash

mkdir -p ./logs/ocr/export/Servo
scp -r abc@10.11.255.23:/mnt/disk/abc/font-ocr/logs/* ./logs/ocr/