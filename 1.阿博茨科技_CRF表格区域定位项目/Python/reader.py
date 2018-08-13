# -*- coding: utf-8 -*-
"""
  Created by dhu on 2018/4/11.
"""
import functools
import os

import tensorflow as tf
import numpy as np
import cv2
from hbconfig import Config
from data_loader import load_vocab


def _parse_tfexample_fn(serialized_example):
    features = tf.parse_single_example(
        serialized_example,
        # Defaults are not specified since both keys are required.
        features={
            "image/encoded": tf.FixedLenFeature((), tf.string, default_value=''),
            "image/class/label": tf.FixedLenFeature([], dtype=tf.int64),
            "image/class/text": tf.FixedLenFeature([], tf.string, default_value='')
        }
    )
    image = tf.image.decode_png(features["image/encoded"], channels=3)
    image.set_shape([64, 64, 3])
    image = tf.image.convert_image_dtype(image, dtype=tf.float32)
    image = tf.image.rgb_to_grayscale(image)
    label = features['image/class/label']
    text = features['image/class/text']
    return image, label, text


def get_all_records(tfrecord_pattern):

    with tf.Session() as sess:
        servo_dir = '/Users/dhu/code/paragraph_classfication/logs/ocr/export/Servo'
        latest_dir = max([os.path.join(servo_dir, d) for d in os.listdir(servo_dir) if os.path.isdir(os.path.join(servo_dir, d))], key=os.path.getmtime)
        tf.saved_model.loader.load(sess, ["serve"], latest_dir)

        # write graph
        train_writer = tf.summary.FileWriter('/Users/dhu/code/paragraph_classfication/logs/ocr')
        train_writer.add_graph(sess.graph)

        dataset = tf.data.TFRecordDataset.list_files(tfrecord_pattern)
        dataset = dataset.shuffle(buffer_size=100)
        dataset = dataset.repeat()
        # Preprocesses 10 files concurrently and interleaves records from each file.
        dataset = dataset.interleave(
            tf.data.TFRecordDataset,
            cycle_length=10,
            block_length=1)
        dataset = dataset.map(
            functools.partial(_parse_tfexample_fn),
            num_parallel_calls=10)

        dataset = dataset.shuffle(buffer_size=1000)

        batch_size = 9
        # Our inputs are variable length, so pad them.
        dataset = dataset.padded_batch(
            batch_size, padded_shapes=dataset.output_shapes)
        iterator = dataset.make_initializable_iterator()
        image_tensor, label_tensor, text_tensor = iterator.get_next()
        init_op = tf.initialize_all_variables()
        sess.run(init_op)
        sess.run(iterator.initializer)
        coord = tf.train.Coordinator()
        threads = tf.train.start_queue_runners(coord=coord)
        for i in range(1000):
            image, label, text = sess.run([image_tensor, label_tensor, text_tensor])
            image_bytes = []
            for j in range(batch_size):
                print("text_%d: %s" % (j, text[j]))
                rgb_image = cv2.cvtColor(image[j] * 256, cv2.COLOR_GRAY2RGB).astype(int)
                img_encode = cv2.imencode('.png', rgb_image)[1]
                str_encode = img_encode.tostring()
                with open('/Users/dhu/code/paragraph_classfication/logs/ocr/%d.png' % j, "wb") as f:
                    f.write(str_encode)
                image_bytes.append(str_encode)
                cv2.imshow("img_%d" % j, image[j])
            classes, scores, tf_images = sess.run(["class:0", "scores:0", 'map/TensorArrayStack/TensorArrayGatherV3:0'], feed_dict={
                "image_bytes:0": image_bytes
            })
            for j in range(batch_size):
                cv2.imshow("tf_img_%d" % j, tf_images[j])

            print(zip(label, classes))
            cv2.waitKey()
        coord.request_stop()
        coord.join(threads)


def predict(image_file):
    Config('ocr-dev')
    vocab = load_vocab()
    with tf.Session() as sess:
        servo_dir = '/Users/dhu/code/paragraph_classfication/logs/ocr/export/Servo'
        latest_dir = max([os.path.join(servo_dir, d) for d in os.listdir(servo_dir) if os.path.isdir(os.path.join(servo_dir, d))], key=os.path.getmtime)
        tf.saved_model.loader.load(sess, ["serve"], latest_dir)

        with open(image_file, 'rb') as f:
            image_bytes = f.read()
        classes, scores, tf_images = sess.run(["class:0", "scores:0", 'map/TensorArrayStack/TensorArrayGatherV3:0'], feed_dict={
            "image_bytes:0": [image_bytes]
        })
        print(vocab.reverse(classes[0] + 1))
        cv2.imshow('img', tf_images[0])
        cv2.waitKey()

get_all_records('/Users/dhu/Downloads/font-data/SimSun.tfrecord')
# predict('/Users/dhu/code/paragraph_classfication/logs/ocr/2.png')