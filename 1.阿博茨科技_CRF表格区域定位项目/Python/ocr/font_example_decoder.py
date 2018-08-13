# -*- coding: utf-8 -*-
"""
  Created by dhu on 2018/7/2.
"""
import tensorflow as tf

def parse_tfexample_fn(example_proto, mode=None):
    """Parse a single record which is expected to be a tensorflow.Example."""
    feature_to_type = {
        "image/encoded": tf.FixedLenFeature((), tf.string, default_value=''),
    }
    if mode != tf.estimator.ModeKeys.PREDICT:
        # The labels won't be available at inference time, so don't add them
        # to the list of feature_columns to be read.
        feature_to_type["image/class/text"] = tf.FixedLenFeature([], tf.string, default_value='')

    parsed_features = tf.parse_single_example(example_proto, feature_to_type)
    labels = None
    if mode != tf.estimator.ModeKeys.PREDICT:
        labels = parsed_features["image/class/text"]

    image = tf.decode_raw(parsed_features["image/encoded"], tf.uint8)
    image = tf.reshape(image, [64, 64, 1])
    image = tf.image.convert_image_dtype(image, dtype=tf.float32)

    return {'image': image}, labels


if __name__ == "__main__":
    import sys
    import cv2

    with tf.Session() as sess:
        filename_queue = tf.train.string_input_producer([ sys.argv[1] ])
        reader = tf.TFRecordReader()
        _, serialized_example = reader.read(filename_queue)
        image, label = parse_tfexample_fn(serialized_example)

        init_op = tf.initialize_all_variables()
        sess.run(init_op)
        coord = tf.train.Coordinator()
        threads = tf.train.start_queue_runners(coord=coord)
        for i in range(10000):
            example, l = sess.run([image, label])
            if i < 1000:
                continue
            print(l)
            cv2.imshow('font', example['image'])
            cv2.waitKey()

        coord.request_stop()
        coord.join(threads)
