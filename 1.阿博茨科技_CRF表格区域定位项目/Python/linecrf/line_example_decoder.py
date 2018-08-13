# -*- coding: utf-8 -*-
"""
  Created by dhu on 2018/5/3.
"""
import argparse

import logging
import os
import tensorflow as tf
from hbconfig import Config
from tensorflow.python.framework import sparse_tensor

slim_example_decoder = tf.contrib.slim.tfexample_decoder


class LookupTensor(slim_example_decoder.Tensor):
    """An ItemHandler that returns a parsed Tensor, the result of a lookup."""

    def __init__(self,
                 tensor_key,
                 table,
                 shape_keys=None,
                 shape=None,
                 default_value=''):
        """Initializes the LookupTensor handler.

        See Tensor.  Simply calls a vocabulary (most often, a label mapping) lookup.

        Args:
          tensor_key: the name of the `TFExample` feature to read the tensor from.
          table: A tf.lookup table.
          shape_keys: Optional name or list of names of the TF-Example feature in
            which the tensor shape is stored. If a list, then each corresponds to
            one dimension of the shape.
          shape: Optional output shape of the `Tensor`. If provided, the `Tensor` is
            reshaped accordingly.
          default_value: The value used when the `tensor_key` is not found in a
            particular `TFExample`.

        Raises:
          ValueError: if both `shape_keys` and `shape` are specified.
        """
        self._table = table
        super(LookupTensor, self).__init__(tensor_key, shape_keys, shape,
                                           default_value)

    def tensors_to_item(self, keys_to_tensors):
        unmapped_tensor = super(LookupTensor, self).tensors_to_item(keys_to_tensors)
        return self._table.lookup(unmapped_tensor)


class Line(slim_example_decoder.ItemHandler):
    """An ItemHandler that concatenates a set of parsed Tensors to Bounding Boxes.
    """

    def __init__(self):
        """Initialize the bounding box handler.

        Args:
          keys: A list of four key names representing the ymin, xmin, ymax, mmax
          prefix: An optional prefix for each of the bounding box keys.
            If provided, `prefix` is appended to each key in `keys`.

        Raises:
          ValueError: if keys is not `None` and also not a list of exactly 4 keys
         
                                                                                    
        """
        self._keys = ['font_size', 'bold', 'italic', 'column_count',
                      'left', 'top', 'width', 'height', 'left_diff', 'right_diff',
                      'prev_left_diff', 'prev_right_diff', 'prev_top_diff', 'prev_bottom_diff',
                      'next_left_diff', 'next_right_diff', 'next_top_diff', 'next_bottom_diff',
                      'has_top_ruling', 'has_bottom_ruling', 'number_num', 'number_ratio',
                      'pattern_match_result', 'has_fill_area', 'has_ruling_region',

                      'left_layout', 'top_layout', 'width_layout', 'height_layout', 'left_diff_layout','right_diff_layout',
                      'pre_left_diff_layout', 'pre_right_diff_layout', 'pre_top_diff_layout', 'pre_bottom_diff_layout',
                      'next_left_diff_layout', 'next_right_diff_layout', 'next_top_diff_layout','next_bottom_diff_layout',

                      'has_bottom_space_line', 'has_top_space_line',"has_prev_similar_table_line","has_next_similar_table_line",
                      "prev_space_num_ratio","next_space_num_ratio","number_text_chunks","space_ratio"
                      ]
        super(Line, self).__init__(self._keys)

    def tensors_to_item(self, keys_to_tensors):
        """Maps the given dictionary of tensors to a contatenated list of bboxes.

        Args:
          keys_to_tensors: a mapping of TF-Example keys to parsed tensors.

        Returns:
          [num_boxes, 4] tensor of bounding box coordinates,
            i.e. 1 bounding box per row, in order [y_min, x_min, y_max, x_max].
        """
        sides = []
        for key in self._keys:
            side = keys_to_tensors[key]
            if isinstance(side, sparse_tensor.SparseTensor):
                side = tf.to_float(side.values)
                if key is 'pattern_match_result':
                    side = tf.reshape(side, [-1, 9])
                    side = tf.transpose(side)
                else:
                    side = tf.expand_dims(side, 0)
                sides.append(side)
        line = tf.concat(sides, 0)
        return tf.transpose(line)


class LineExampleDecoder(object):
    """Tensorflow Example proto decoder."""

    def __init__(self):
        self.keys_to_features = {
            'line_count': tf.FixedLenFeature((), tf.int64, default_value=0),
            'text': tf.VarLenFeature(tf.string),
            'text_length': tf.VarLenFeature(tf.int64),
            'font_size': tf.VarLenFeature(tf.float32),
            'tags': tf.VarLenFeature(tf.int64),
            'bold': tf.VarLenFeature(tf.int64),
            'italic': tf.VarLenFeature(tf.int64),
            'left': tf.VarLenFeature(tf.float32),
            'top': tf.VarLenFeature(tf.float32),
            'width': tf.VarLenFeature(tf.float32),
            'height': tf.VarLenFeature(tf.float32),
            'column_count': tf.VarLenFeature(tf.int64),
            'left_diff': tf.VarLenFeature(tf.float32),
            'right_diff': tf.VarLenFeature(tf.float32),
            'prev_left_diff': tf.VarLenFeature(tf.float32),
            'prev_right_diff': tf.VarLenFeature(tf.float32),
            'prev_top_diff': tf.VarLenFeature(tf.float32),
            'prev_bottom_diff': tf.VarLenFeature(tf.float32),
            'next_left_diff': tf.VarLenFeature(tf.float32),
            'next_right_diff': tf.VarLenFeature(tf.float32),
            'next_top_diff': tf.VarLenFeature(tf.float32),
            'next_bottom_diff': tf.VarLenFeature(tf.float32),
            'has_top_ruling': tf.VarLenFeature(tf.int64),
            'has_bottom_ruling': tf.VarLenFeature(tf.int64),
            'number_num': tf.VarLenFeature(tf.int64),
            'number_ratio': tf.VarLenFeature(tf.float32),
            'pattern_match_result': tf.VarLenFeature(tf.int64),
            'has_fill_area': tf.VarLenFeature(tf.int64),
            'has_ruling_region': tf.VarLenFeature(tf.int64),

            'left_layout': tf.VarLenFeature(tf.float32),
            'top_layout': tf.VarLenFeature(tf.float32),
            'width_layout': tf.VarLenFeature(tf.float32),
            'height_layout': tf.VarLenFeature(tf.float32),
            'left_diff_layout': tf.VarLenFeature(tf.float32),
            'right_diff_layout': tf.VarLenFeature(tf.float32),
            'pre_left_diff_layout': tf.VarLenFeature(tf.float32),
            'pre_right_diff_layout': tf.VarLenFeature(tf.float32),
            'pre_top_diff_layout': tf.VarLenFeature(tf.float32),
            'pre_bottom_diff_layout': tf.VarLenFeature(tf.float32),
            'next_left_diff_layout': tf.VarLenFeature(tf.float32),
            'next_right_diff_layout': tf.VarLenFeature(tf.float32),
            'next_top_diff_layout': tf.VarLenFeature(tf.float32),
            'next_bottom_diff_layout': tf.VarLenFeature(tf.float32),

            'has_top_space_line':tf.VarLenFeature(tf.int64),
            'has_bottom_space_line': tf.VarLenFeature(tf.int64),
            "has_prev_similar_table_line":tf.VarLenFeature(tf.int64),
            "has_next_similar_table_line":tf.VarLenFeature(tf.int64),
            "next_space_num_ratio":tf.VarLenFeature(tf.float32),
            "prev_space_num_ratio":tf.VarLenFeature(tf.float32),
            'number_text_chunks':tf.VarLenFeature(tf.int64),
            'space_ratio':tf.VarLenFeature(tf.float32),
        }
        self.items_to_handlers = {
            'line_count': slim_example_decoder.Tensor('line_count'),
            'tags': slim_example_decoder.Tensor('tags'),
            'text': slim_example_decoder.Tensor('text', default_value=''),
            'text_length': slim_example_decoder.Tensor('text_length'),
            'line': Line(),
            # 'font_size': (slim_example_decoder.Tensor('font_size')),
            # 'tags': (slim_example_decoder.Tensor('tags')),
            # 'bold': (slim_example_decoder.Tensor('bold')),
            # 'italic': (slim_example_decoder.Tensor('italic')),
            # 'left': (slim_example_decoder.Tensor('left')),
            # 'top': (slim_example_decoder.Tensor('top')),
            # 'width': (slim_example_decoder.Tensor('width')),
            # 'height': (slim_example_decoder.Tensor('height')),
            # 'column_count': (slim_example_decoder.Tensor('column_count')),
            # 'left_diff': (slim_example_decoder.Tensor('left_diff')),
            # 'right_diff': (slim_example_decoder.Tensor('right_diff')),
            # 'prev_left_diff': (slim_example_decoder.Tensor('prev_left_diff')),
            # 'prev_right_diff': (slim_example_decoder.Tensor('prev_right_diff')),
            # 'prev_top_diff': (slim_example_decoder.Tensor('prev_top_diff')),
            # 'prev_bottom_diff': (slim_example_decoder.Tensor('prev_bottom_diff')),
            # 'next_left_diff': (slim_example_decoder.Tensor('next_left_diff')),
            # 'next_right_diff': (slim_example_decoder.Tensor('next_right_diff')),
            # 'next_top_diff': (slim_example_decoder.Tensor('next_top_diff')),
            # 'next_bottom_diff': (slim_example_decoder.Tensor('next_bottom_diff')),
        }

    def decode(self, tf_example_string_tensor):
        serialized_example = tf.reshape(tf_example_string_tensor, shape=[])
        decoder = slim_example_decoder.TFExampleDecoder(self.keys_to_features, self.items_to_handlers)
        keys = decoder.list_items()
        tensors = decoder.decode(serialized_example, items=keys)
        tensor_dict = dict(zip(keys, tensors))
        return tensor_dict


def parse_tfexample_fn(example, mode=tf.estimator.ModeKeys.TRAIN):
    decoder = LineExampleDecoder()
    input_dict = decoder.decode(example)
    if mode != tf.estimator.ModeKeys.PREDICT:
        label = input_dict.pop('tags')
    else:
        label = None
    return input_dict, label


if __name__ == '__main__':
# module=eval("/home/jhqiu/git/paragraph_classfication/logs/line-crf2/export","/home/jhqiu/git/paragraph_classfication/data/line-eval-1.tfrecord.tfrecord")


    import sys

    # dataset = tf.data.TFRecordDataset("/home/jhqiu/git/paragraph_classfication/data/line-eval-1.tfrecord")
    dataset = tf.data.TFRecordDataset("/home/jhqiu/git/paragraph_classfication/data/line-train-研报-1.tfrecord")
    dataset = dataset.repeat(1)
    dataset = dataset.map(parse_tfexample_fn)

    def filter_fn(features, labels):
        return features['line_count'] > 0

    dataset = dataset.filter(filter_fn)

    # Our inputs are variable length, so pad them.
    dataset = dataset.padded_batch(
        32, padded_shapes=dataset.output_shapes)
    iterator = dataset.make_one_shot_iterator()
    features, labels = iterator.get_next()
    labels = tf.cast(labels, tf.int32)
    with tf.Session() as sess:
        inputs = features['line']
        sess.run(tf.tables_initializer("table"))
        features0, labels0 = sess.run([features, labels])
        hidden_size = 64
        num_tags = 12
        seq_length = features['line_count']
        cell_fw = tf.contrib.rnn.GRUCell(hidden_size)
        cell_bw = tf.contrib.rnn.GRUCell(hidden_size)

        (output_fw, output_bw), state = tf.nn.bidirectional_dynamic_rnn(
            cell_fw, cell_bw, inputs,
            sequence_length=seq_length,
            dtype=tf.float32)

        outputs = tf.concat([output_fw, output_bw], axis=-1)
        nsteps = tf.shape(outputs)[1]
        net = tf.reshape(outputs, [-1, 2 * hidden_size])
        net = tf.layers.dense(net, 64, activation=tf.nn.relu)
        net = tf.layers.dense(net, num_tags)
        logits = tf.reshape(net, [-1, nsteps, num_tags], name='logits')
        log_likelihood, trans_params = tf.contrib.crf.crf_log_likelihood(logits, labels, seq_length)
        viterbi_sequence, viterbi_score = tf.contrib.crf.crf_decode(logits, trans_params, seq_length)

        loss = tf.reduce_mean(-log_likelihood, name='loss')
        accuracy = tf.reduce_mean(tf.to_float(tf.equal(viterbi_sequence, labels)))
        train_op = tf.contrib.layers.optimize_loss(
            loss, tf.train.get_global_step(),
            optimizer='Adam',
            learning_rate=0.01,
            summaries=['loss', 'learning_rate'],
            name="train_op")

        tf.global_variables_initializer().run()
        other_nums = 0
        tabel_nums = 0
        for i in range(10000):
            # acc, loss_ = sess.run([accuracy, loss])
            # print("Step %d, loss: %.5f, accuracy: %.5f" % (i, loss_, acc))
            try:
                f = sess.run(labels)
                print(i)
                for page in f:
                    for l in page:
                        if l >= 6 and l <= 9:
                            tabel_nums += 1
                        elif l != 0:
                            other_nums += 1
            except:
                break
        print(other_nums, tabel_nums)
