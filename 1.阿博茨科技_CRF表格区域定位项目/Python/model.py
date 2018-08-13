# -*- coding: utf-8 -*-
"""
  Created by dhu on 2018/3/9.
"""

from __future__ import print_function


from hbconfig import Config
import tensorflow as tf
from tensorflow.contrib import layers


class Model:

    def __init__(self):
        pass

    def model_fn(self, mode, features, labels, params):
        self.dtype = tf.float32

        self.mode = mode
        self.is_training = self.mode == tf.estimator.ModeKeys.TRAIN
        self.num_steps = Config.model.num_steps
        self.batch_size = Config.model.batch_size if self.is_training else 1
        self.params = params

        self.loss, self.train_op, self.metrics, self.predictions = None, None, None, None
        self._init_placeholder(features, labels)
        self.build_graph()

        # train mode: required loss and train_op
        # eval mode: required loss
        # predict mode: required predictions

        export_outputs = {
            "predict": tf.estimator.export.PredictOutput(
                {"scores": tf.identity(self.scores, "scores"),
                 "class": tf.identity(self.predictions, "class")})}

        return tf.estimator.EstimatorSpec(
            mode=mode,
            loss=self.loss,
            train_op=self.train_op,
            eval_metric_ops=self.metrics,
            predictions={"prediction": self.predictions},
            export_outputs=export_outputs)

    def _init_placeholder(self, features, labels):
        self.input_data = features
        if type(features) == dict:
            self.input_data = features["input_data"]

        self.targets = labels

    def build_graph(self):
        with tf.device("/cpu:0"):
            embedding = tf.get_variable(
                "embedding", [Config.data.vocab_size, Config.model.embed_dim], dtype=tf.float32)
            inputs = tf.nn.embedding_lookup(embedding, self.input_data)

        if self.is_training and Config.model.keep_prob < 1:
            inputs = tf.nn.dropout(inputs, Config.model.keep_prob)

        output = self._build_rnn_graph(inputs, self.is_training)
        output = tf.layers.dense(output, Config.model.hidden_size)
        if self.is_training and Config.model.keep_prob < 1:
            output = tf.nn.dropout(output, Config.model.keep_prob)
        output = tf.nn.relu(output)

        logits = tf.layers.dense(output, Config.data.num_classes)

        self._build_prediction(logits)
        if self.mode != tf.estimator.ModeKeys.PREDICT:
            self._build_loss(logits)
            self._build_optimizer()
            self._build_metric()

    def _get_lstm_cell(self, is_training):
        if Config.model.rnn_mode == 'BASIC':
            return tf.contrib.rnn.BasicLSTMCell(
                Config.model.hidden_size, forget_bias=0.0)
        if Config.model.rnn_mode == 'BLOCK':
            return tf.contrib.rnn.LSTMBlockCell(
                Config.model.hidden_size, forget_bias=0.0)
        raise ValueError("rnn_mode %s not supported" % Config.model.rnn_mode)

    def _build_rnn_graph(self, inputs, is_training):

        def make_cell():
            cell = self._get_lstm_cell(is_training)
            if is_training and Config.model.keep_prob < 1:
                cell = tf.contrib.rnn.DropoutWrapper(
                    cell, output_keep_prob=Config.model.keep_prob)
            return cell

        cell = tf.contrib.rnn.MultiRNNCell(
            [make_cell() for _ in range(Config.model.num_layers)], state_is_tuple=True)

        output, state = tf.nn.dynamic_rnn(cell=cell, inputs=inputs, dtype=tf.float32)
        return output[:, -1, :]  # 取最后一个时序输出作为结果

    def _build_loss(self, output):
        self.loss = tf.losses.softmax_cross_entropy(
                self.targets,
                output,
                scope="loss")

    def _build_prediction(self, output):
        tf.argmax(output[0], name='train/pred_0') # for print_verbose
        self.predictions = tf.argmax(output, axis=1)
        self.scores = tf.nn.softmax(output)

    def _build_optimizer(self):
        self.train_op = layers.optimize_loss(
            self.loss, tf.train.get_global_step(),
            optimizer='Adam',
            learning_rate=Config.train.learning_rate,
            summaries=['loss', 'learning_rate'],
            name="train_op")

    def _build_metric(self):
        self.metrics = {
            "accuracy": tf.metrics.accuracy(tf.argmax(self.targets, axis=1), self.predictions)
        }
