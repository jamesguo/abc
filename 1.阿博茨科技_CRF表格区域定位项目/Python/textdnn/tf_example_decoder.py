# -*- coding: utf-8 -*-
"""
  Created by dhu on 2018/5/3.
"""
import tensorflow as tf

slim_example_decoder = tf.contrib.slim.tfexample_decoder


class TfExampleDecoder(object):
    """Tensorflow Example proto decoder."""

    def __init__(self):
        self.keys_to_features = {
            'text': tf.FixedLenFeature((), tf.string, default_value=''),
            'text_length': tf.FixedLenFeature((), tf.int64, default_value=0),
            'next_text': tf.FixedLenFeature((), tf.string, default_value=''),
            'next_text_length': tf.FixedLenFeature((), tf.int64, default_value=0),
            'tags': tf.VarLenFeature(tf.int64),
            'margin': tf.FixedLenFeature([4], dtype=tf.float32),
            'indent': tf.FixedLenFeature([4], dtype=tf.float32),
            'font_size': tf.FixedLenFeature([2], dtype=tf.float32),
            'can_merge': tf.FixedLenFeature((), tf.int64, default_value=0),
        }
        self.items_to_handlers = {
            'text': (slim_example_decoder.Tensor('text')),
            'text_length': (slim_example_decoder.Tensor('text_length')),
            'next_text': (slim_example_decoder.Tensor('next_text')),
            'next_text_length': (slim_example_decoder.Tensor('next_text_length')),
            'tags': (slim_example_decoder.Tensor('tags')),
            'margin': (slim_example_decoder.Tensor('margin')),
            'indent': (slim_example_decoder.Tensor('indent')),
            'font_size': (slim_example_decoder.Tensor('font_size')),
            'can_merge': (slim_example_decoder.Tensor('can_merge')),
        }

    def decode(self, tf_example_string_tensor):
        serialized_example = tf.reshape(tf_example_string_tensor, shape=[])
        decoder = slim_example_decoder.TFExampleDecoder(self.keys_to_features, self.items_to_handlers)
        keys = decoder.list_items()
        tensors = decoder.decode(serialized_example, items=keys)
        tensor_dict = dict(zip(keys, tensors))
        return tensor_dict