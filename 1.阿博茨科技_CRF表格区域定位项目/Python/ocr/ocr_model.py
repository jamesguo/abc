# -*- coding: utf-8 -*-
"""
  Created by dhu on 2018/3/9.
"""

from __future__ import print_function

import os

import tensorflow as tf
from tensorflow.contrib import layers
from tensorflow.contrib.framework import add_arg_scope
from tensorflow.contrib.framework import arg_scope

from hbconfig import Config

slim = tf.contrib.slim

class Model:

    def __init__(self):
        pass

    def model_fn(self, mode, features, labels, params):
        self.mode = mode
        self.is_training = self.mode == tf.estimator.ModeKeys.TRAIN
        self.batch_size = params.batch_size
        self.params = params

        vocab_file = os.path.join(Config.data.base_path, 'vocab.txt')
        self.table = tf.contrib.lookup.index_table_from_file(
            vocabulary_file=vocab_file,
            default_value=0
        )
        self.id_to_text_table = tf.contrib.lookup.index_to_string_table_from_file(
            vocabulary_file=vocab_file,
            default_value='<UNK>'
        )

        self.loss, self.train_op, self.metrics, self.predictions = None, None, None, None
        self._init_placeholder(features, labels)

        self.build_graph()

        # train mode: required loss and train_op
        # eval mode: required loss
        # predict mode: required predictions

        export_outputs = {
            "predict": tf.estimator.export.PredictOutput(
                {
                    "texts": tf.identity(self.texts, "texts"),
                    "top3_scores": tf.identity(self.top3_scores, "top3_scores"),
                    "top3_texts": tf.identity(self.top3_texts, "top3_texts"),
                 })
        }

        scaffold = tf.train.Scaffold(
            ready_op=tf.tables_initializer(),
        )

        return tf.estimator.EstimatorSpec(
            mode=mode,
            loss=self.loss,
            train_op=self.train_op,
            eval_metric_ops=self.metrics,
            predictions={
                "texts": self.texts,
                "top3_scores": self.top3_scores,
                "top3_texts": self.top3_texts
            },
            scaffold=scaffold,
            export_outputs=export_outputs)

    def _init_placeholder(self, features, labels):
        self.input_data = features
        if type(features) == dict:
            self.input_data = features["image"]

        # if self.is_training:
        #     self.input_data = self.data_augmentation(self.input_data)

        tf.summary.image('images', self.input_data, max_outputs=36)
        if labels is not None:
            labels = self.table.lookup(labels)
        self.targets = labels

    def build_graph(self):
        # with slim.arg_scope(nasnet.nasnet_cifar_arg_scope()):
        #     logits, end_points = nasnet.build_nasnet_cifar(self.input_data, Config.data.num_classes)
        inputs = self.input_data
        num_classes = Config.data.vocab_size
        if Config.model.net == 'cnn':
            logits, end_points = self._build_cnn(self.is_training, inputs, num_classes)
        elif Config.model.net == 'cnn-small':
            logits, end_points = self._build_cnn_small(self.is_training, inputs, num_classes)
        else:
            raise NameError('Unknown net: ' + Config.model.net)

        self._build_prediction(end_points)
        if self.mode != tf.estimator.ModeKeys.PREDICT:
            self._build_loss(logits)
            self._build_optimizer()
            self._build_metric()

    @staticmethod
    def data_augmentation(image):
        with tf.name_scope('image_distort'):
            image = tf.image.random_brightness(image, max_delta=32. / 255.)
            return tf.clip_by_value(image, 0.0, 1.0)

    @staticmethod
    def _build_cnn(is_training, inputs, num_classes, keep_prob=0.8):
        with slim.arg_scope([slim.conv2d, slim.fully_connected],
                            weights_regularizer=layers.l2_regularizer(0.0005),
                            normalizer_fn=slim.batch_norm,
                            normalizer_params={'is_training': is_training}):
            conv3_1 = slim.conv2d(inputs, 64, [3, 3], 1, padding='SAME', scope='conv3_1')
            max_pool_1 = slim.max_pool2d(conv3_1, [2, 2], [2, 2], padding='SAME', scope='pool1')
            conv3_2 = slim.conv2d(max_pool_1, 128, [3, 3], padding='SAME', scope='conv3_2')
            max_pool_2 = slim.max_pool2d(conv3_2, [2, 2], [2, 2], padding='SAME', scope='pool2')
            conv3_3 = slim.conv2d(max_pool_2, 256, [3, 3], padding='SAME', scope='conv3_3')
            max_pool_3 = slim.max_pool2d(conv3_3, [2, 2], [2, 2], padding='SAME', scope='pool3')
            conv3_4 = slim.conv2d(max_pool_3, 512, [3, 3], padding='SAME', scope='conv3_4')
            conv3_5 = slim.conv2d(conv3_4, 512, [3, 3], padding='SAME', scope='conv3_5')
            max_pool_4 = slim.max_pool2d(conv3_5, [2, 2], [2, 2], padding='SAME', scope='pool4')

            flatten = slim.flatten(max_pool_4)
            fc1 = slim.fully_connected(slim.dropout(flatten, keep_prob,is_training=is_training), 1024,
                                       activation_fn=tf.nn.relu, scope='fc1')
            logits = slim.fully_connected(slim.dropout(fc1, keep_prob,is_training=is_training), num_classes, activation_fn=None,
                                          scope='fc2')
            end_points = {
                'Logits': logits,
                'Predictions': tf.nn.softmax(logits, name='Predictions')
            }
            return logits, end_points

    @staticmethod
    def _build_cnn_small(is_training, inputs, num_classes, keep_prob=0.8):
        with slim.arg_scope([slim.conv2d, slim.fully_connected],
                            weights_regularizer=layers.l2_regularizer(0.0005),
                            normalizer_fn=slim.batch_norm,
                            normalizer_params={'is_training': is_training}):
            net = slim.conv2d(inputs, 32, [3, 3], 1, padding='SAME', scope='conv3_1')
            net = slim.conv2d(net, 32, [3, 3], 1, padding='SAME', scope='conv3_2')
            net = slim.max_pool2d(net, [2, 2], [2, 2], padding='SAME', scope='pool1')
            net = slim.conv2d(net, 64, [3, 3], padding='SAME', scope='conv3_3')
            net = slim.max_pool2d(net, [2, 2], [2, 2], padding='SAME', scope='pool2')
            net = slim.conv2d(net, 128, [3, 3], padding='SAME', scope='conv3_4')
            net = slim.max_pool2d(net, [2, 2], [2, 2], padding='SAME', scope='pool3')
            net = slim.conv2d(net, 256, [3, 3], padding='SAME', scope='conv3_5')
            net = slim.max_pool2d(net, [2, 2], [2, 2], padding='SAME', scope='pool4')

            net = slim.flatten(net)
            net = slim.fully_connected(slim.dropout(net, keep_prob,is_training=is_training), 512,
                                       activation_fn=tf.nn.relu, scope='fc1')
            logits = slim.fully_connected(slim.dropout(net, keep_prob,is_training=is_training), num_classes, activation_fn=None,
                                          scope='fc2')
            end_points = {
                'Logits': logits,
                'Predictions': tf.nn.softmax(logits, name='Predictions')
            }
            return logits, end_points


    def _build_loss(self, output):
        self.loss = tf.losses.sparse_softmax_cross_entropy(
                self.targets,
                output,
                scope="loss")

    def _build_prediction(self, end_points):
        self.scores = end_points['Predictions']
        self.predictions = tf.argmax(self.scores, axis=1)
        self.texts = self.id_to_text_table.lookup(self.predictions)
        self.top3 = tf.nn.top_k(self.scores, k=3)
        self.top3_scores = self.top3.values
        self.top3_texts = self.id_to_text_table.lookup(tf.cast(self.top3.indices, tf.int64))

    def _build_optimizer(self):
        self.train_op = layers.optimize_loss(
            self.loss, tf.train.get_global_step(),
            optimizer='Adam',
            learning_rate=Config.train.learning_rate,
            summaries=['loss', 'learning_rate'],
            name="train_op")

    def _build_metric(self):
        self.metrics = {
            "accuracy": tf.metrics.accuracy(self.targets, self.predictions),
            "top3_accuracy": tf.metrics.mean(tf.nn.in_top_k(predictions=self.scores, targets=self.targets, k=3))
        }
