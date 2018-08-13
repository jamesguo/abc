# -*- coding: utf-8 -*-
"""
  Created by dhu on 2018/5/3.
"""
import os

import tensorflow as tf
from tensorflow.contrib import layers
from hbconfig import Config
from linecrf import crnn

slim = tf.contrib.slim


PAD = 0
UNK = 1

class Model:

    def __init__(self):
        pass

    def model_fn(self, mode, features, labels, params):
        self.mode = mode
        self.is_training = self.mode == tf.estimator.ModeKeys.TRAIN
        self.batch_size = params.batch_size
        self.params = params

        self.loss, self.train_op, self.metrics = None, None, None
        self.viterbi_sequence, self.viterbi_score = None, None
        self.features, self.targets = None, None
        self._init_placeholder(features, labels)
        self.build_graph()

        # train mode: required loss and train_op
        # eval mode: required loss
        # predict mode: required predictions

        export_outputs = {
            "predict": tf.estimator.export.PredictOutput(
                {
                    "crf_tags": self.viterbi_sequence,
                    "crf_scores": self.viterbi_score,
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
            scaffold=None,
            predictions={"prediction": self.viterbi_sequence},
            export_outputs=export_outputs)

    def _init_placeholder(self, features, labels):
        self.features = features
        # print(features)
        keys = tf.constant(Config.model.tag_keys)
        values = tf.constant(Config.model.tag_values)
        if keys is not None and keys.dtype != tf.int32:
            keys = tf.cast(keys, tf.int32)
        if labels is not None and labels.dtype != tf.int32:
            labels = tf.cast(labels, tf.int32)
        table = tf.contrib.lookup.HashTable(tf.contrib.lookup.KeyValueTensorInitializer(keys, values), -1)
        if labels is not None:
            self.targets = table.lookup(labels)
        self.table = table
        # self.targets = labels

    def build_graph(self):
        self._build_net(self.is_training, self.features)
        self._build_prediction()
        if self.mode != tf.estimator.ModeKeys.PREDICT:
            self._build_loss()
            self._build_optimizer()
            self._build_metric()

    def _get_lstm_cell(self, is_training):
        if Config.model.rnn_mode == 'BASIC':
            return tf.contrib.rnn.BasicLSTMCell(
                Config.model.hidden_size, forget_bias=0.0)
        elif Config.model.rnn_mode == 'LSTM':
            return tf.contrib.rnn.LSTMCell(
                Config.model.hidden_size, forget_bias=0.0)
        elif Config.model.rnn_mode == 'GRU':
            return tf.contrib.rnn.GRUCell(Config.model.hidden_size)
        raise ValueError("rnn_mode %s not supported" % Config.model.rnn_mode)

    def _build_rnn_graph(self):
        num_tags = Config.data.num_classes

        def make_cell():
            cell = self._get_lstm_cell(self.is_training)
            if self.is_training and Config.model.keep_prob < 1:
                cell = tf.contrib.rnn.DropoutWrapper(
                    cell, output_keep_prob=Config.model.keep_prob)
            return cell

        cell_fw = make_cell()
        cell_bw = make_cell()

        outputs, state = tf.nn.bidirectional_dynamic_rnn(
            cell_fw, cell_bw, self.inputs,
            sequence_length=self.seq_length,
            dtype=tf.float32)
        outputs = tf.concat(outputs, axis=-1)
        nsteps = tf.shape(outputs)[1]
        net = tf.reshape(outputs, [-1, 2 * Config.model.hidden_size])
        net = tf.layers.dense(net, 64, activation=tf.nn.relu)
        net = tf.layers.dense(net, num_tags)
        self.logits = tf.reshape(net, [-1, nsteps, num_tags], name='logits')
        self.trans_params = tf.get_variable("trans_params", [num_tags, num_tags])

    def _build_cnn_graph(self):
        num_tags = Config.data.num_classes
        inputs = tf.expand_dims(self.inputs, axis=-1)
        with slim.arg_scope([slim.conv2d, slim.fully_connected],
                            weights_regularizer=layers.l2_regularizer(0.0005),
                            normalizer_fn=slim.batch_norm,
                            normalizer_params={'is_training': self.is_training}):
            conv1 = slim.conv2d(inputs, 64, [1, 1], 1, padding='SAME', scope='conv1')
            conv2 = slim.conv2d(inputs, 64, [2, 1], 1, padding='SAME', scope='conv2')
            conv3 = slim.conv2d(inputs, 64, [4, 1], 1, padding='SAME', scope='conv3')
            conv4 = slim.conv2d(inputs, 64, [8, 1], stride=1, padding='SAME', scope='conv4')
            output = tf.concat([conv1, conv2, conv3, conv4], axis=-1)
            nsteps = tf.shape(output)[1]
            net = tf.reshape(output, [-1, 64*4*Config.model.vector_lenth])
            net = slim.fully_connected(net, 64, activation_fn=tf.nn.relu)
            net = slim.dropout(net, keep_prob=Config.model.keep_prob, is_training=self.is_training)
            net = slim.fully_connected(net, num_tags, activation_fn=None)
            self.logits = tf.reshape(net, [-1, nsteps, num_tags], name='logits')
            self.trans_params = tf.get_variable("trans_params", [num_tags, num_tags])

    def _build_crnn_graph(self):
        inputs = tf.expand_dims(self.inputs, axis=-1)
        crnn_model=crnn.CRNN(inputs,self.seq_length,self.is_training)
        self.logits = crnn_model.crnn()
        self.trans_params = tf.get_variable("trans_params", [Config.data.num_classes, Config.data.num_classes])


    def _build_net(self, is_training, features):
        self._build_input_vector(features)
        self.seq_length = features['line_count']
        if Config.train.useCNN:
            self._build_cnn_graph()
        elif Config.train.useCRNN:
            self._build_crnn_graph()
        else:
            self._build_rnn_graph()

    def _build_loss(self):
        self.log_likelihood, _ = tf.contrib.crf.crf_log_likelihood(
            self.logits, self.targets, self.seq_length, transition_params=self.trans_params)
        self.loss = tf.reduce_mean(-self.log_likelihood, name='crf_loss')

    def _build_prediction(self):
        self.viterbi_sequence, self.viterbi_score = tf.contrib.crf.crf_decode(
            self.logits, self.trans_params, self.seq_length)
        self.viterbi_sequence = tf.identity(self.viterbi_sequence, 'crf_tags')
        self.viterbi_score = tf.identity(self.viterbi_score, 'crf_scores')

    def _build_optimizer(self):
        self.train_op = layers.optimize_loss(
            self.loss,
            tf.train.get_global_step(),
            learning_rate=Config.train.learning_rate,
            optimizer='Adam',
            summaries=['loss', 'learning_rate', 'gradients', 'gradient_norm', 'global_gradient_norm'],
            name="train_op")

        # 组合文本特征
    def _build_input_vector(self, features):
        self.inputs = features['line']
        if Config.train.useText:
            self.inputs = tf.concat([features['line'], features['text']], -1)

    def get_page_accurate(self):
        c = tf.cast(tf.equal(self.viterbi_sequence, self.targets), dtype=tf.float32)
        d = tf.reduce_prod(c, 1)
        return tf.metrics.mean(d)

    def get_table_accurate(self):
        TABLE_START = 2
        SINGLE_LINE_TABLE = 3
        table_weight1 = tf.greater_equal(self.viterbi_sequence, TABLE_START)
        table_weight2 = tf.less_equal(self.viterbi_sequence, SINGLE_LINE_TABLE)
        table_weight = tf.cast(tf.equal(table_weight1, table_weight2), tf.int32)
        return tf.metrics.accuracy(self.viterbi_sequence, self.targets, weights=table_weight)

    def _build_metric(self):
        PARAGRAPH_START = 2
        SINGLE_LINE_PARAGRAPH = 5
        TABLE_START = 2
        SINGLE_LINE_TABLE = 3
        paragraph_weight1 = tf.greater_equal(self.targets, PARAGRAPH_START)
        paragraph_weight2 = tf.less_equal(self.targets, SINGLE_LINE_PARAGRAPH)
        paragraph_weight = tf.cast(tf.equal(paragraph_weight1, paragraph_weight2), tf.int32)

        table_weight1 = tf.greater_equal(self.targets, TABLE_START)
        table_weight2 = tf.less_equal(self.targets, SINGLE_LINE_TABLE)
        table_weight = tf.cast(tf.equal(table_weight1, table_weight2), tf.int32)
        self.metrics = {
            "accuracy": tf.metrics.accuracy(self.viterbi_sequence, self.targets),
            "paragraph_accuracy": tf.metrics.accuracy(self.viterbi_sequence, self.targets, weights=paragraph_weight),
            "table_recall": tf.metrics.accuracy(self.viterbi_sequence, self.targets, weights=table_weight),
            "table_accuracy": self.get_table_accurate(),
            "total accuracy": self.get_page_accurate(),
        }
