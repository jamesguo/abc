# -*- coding: utf-8 -*-
"""
  Created by dhu on 2018/5/4.
"""
import sys
import tensorflow as tf
import webbrowser
import time
from tensorboard.main import run_main
import os
import threading

servo_dir = sys.argv[1]
log_dir = os.path.join(servo_dir, 'logs')
with tf.Session() as sess:
    tf.saved_model.loader.load(sess, ["serve"], servo_dir)
    train_writer = tf.summary.FileWriter(log_dir)
    train_writer.add_graph(sess.graph)

tf.flags.FLAGS.logdir = log_dir
thread = threading.Thread(target=run_main)
thread.daemon = True
thread.start()
time.sleep(2)
webbrowser.open("http://127.0.0.1:6006/#graphs")
try:
    while True:
        time.sleep(60 * 60 * 24)
except KeyboardInterrupt:
    pass