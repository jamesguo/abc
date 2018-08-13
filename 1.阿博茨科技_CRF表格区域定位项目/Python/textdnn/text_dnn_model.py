# -*- coding: utf-8 -*-
"""
  Created by dhu on 2018/5/3.
"""
import os

import tensorflow as tf
from tensorflow.contrib import layers
from hbconfig import Config

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

        self.loss, self.train_op, self.metrics, self.predictions = None, None, None, None
        self.text_loss, self.total_loss = None, None
        vocab_file = os.path.join(Config.data.base_path, 'vocab.txt')
        self.table = tf.contrib.lookup.index_table_from_file(
            vocabulary_file=vocab_file,
            default_value=UNK
        )
        self._init_placeholder(features, labels)

        self.build_graph()

        # train mode: required loss and train_op
        # eval mode: required loss
        # predict mode: required predictions

        export_outputs = {
            "predict": tf.estimator.export.PredictOutput(
                {
                    "scores": tf.identity(self.scores, "scores"),
                    "class": tf.identity(self.predictions, "class"),
                    "crf_tags": tf.identity(self.viterbi_sequence, "crf_tags"),
                })
        }

        return tf.estimator.EstimatorSpec(
            mode=mode,
            loss=self.total_loss,
            train_op=self.train_op,
            eval_metric_ops=self.metrics,
            predictions={"prediction": self.predictions},
            export_outputs=export_outputs)

    def _init_placeholder(self, features, labels):
        self.features = features
        # print(features)
        self.targets = labels

    def build_graph(self):
        num_classes = Config.data.num_classes
        logits, end_points = self._build_net(self.is_training, self.features, num_classes)

        self._build_prediction(end_points)
        if self.mode != tf.estimator.ModeKeys.PREDICT:
            self._build_loss(logits)
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

    def _build_rnn_graph(self, inputs, seq_length, is_training):

        def make_cell():
            cell = self._get_lstm_cell(is_training)
            if is_training and Config.model.keep_prob < 1:
                cell = tf.contrib.rnn.DropoutWrapper(
                    cell, output_keep_prob=Config.model.keep_prob)
            return cell

        cell_fw = make_cell()
        cell_bw = make_cell()

        outputs, state = tf.nn.bidirectional_dynamic_rnn(
            cell_fw, cell_bw, inputs,
            sequence_length=seq_length,
            dtype=tf.float32)
        return tf.concat(outputs, axis=-1)

    def _build_net(self, is_training, features, num_classes):
        with tf.name_scope('pre_processing'):
            text = features['text']
            next_text = features['next_text']
            total_text = tf.string_join([text, next_text], " ", name='total_text')
            total_text_words = tf.string_split(total_text, delimiter=' ')
            total_text_ids = self.table.lookup(total_text_words)
            total_text_ids = tf.sparse_tensor_to_dense(total_text_ids, default_value=0)
            total_text_ids = tf.cast(total_text_ids, tf.int32, name='total_text_ids')
            text_length = features['text_length']
            text_length = tf.cast(text_length, tf.int32)
            next_text_length = features['next_text_length']
            next_text_length = tf.cast(next_text_length, tf.int32)
            self.seq_length = tf.add(text_length, next_text_length, name='seq_length')
            if 'tags' in features:
                tags = features['tags']
                self.tags = tf.cast(tags, tf.int32, name='tags')
            else:
                self.tags = None

        with tf.name_scope('text_graph'):
            with tf.device("/cpu:0"):
                embedding = tf.get_variable(
                    "embedding", [Config.data.vocab_size, Config.model.embed_dim], dtype=tf.float32)
                inputs = tf.nn.embedding_lookup(embedding, total_text_ids)
            with tf.name_scope('rnn'):
                rnn = self._build_rnn_graph(inputs, self.seq_length, is_training)
            rnn = slim.dropout(rnn, keep_prob=Config.model.keep_prob,is_training=is_training)
            num_tags = 4
            with tf.variable_scope("proj"):
                self.crf_w = tf.get_variable("crf_w", dtype=tf.float32,
                                    shape=[2*Config.model.hidden_size, num_tags])

                self.crf_b = tf.get_variable("crf_b", shape=[num_tags],
                                    dtype=tf.float32, initializer=tf.zeros_initializer())

                nsteps = tf.shape(rnn)[1]
                output = tf.reshape(rnn, [-1, 2*Config.model.hidden_size])
                pred = tf.matmul(output, self.crf_w) + self.crf_b
                self.text_logits = tf.reshape(pred, [-1, nsteps, num_tags], name='text_logits')
            with tf.name_scope('crf'):
                trans_params = tf.get_variable("transitions", [num_tags, num_tags])
                self.trans_params = tf.identity(trans_params, 'trans_params')
                with tf.name_scope('crf_decode'):
                    viterbi_sequence, viterbi_score = tf.contrib.crf.crf_decode(self.text_logits, self.trans_params, self.seq_length)
                    self.viterbi_sequence = tf.identity(viterbi_sequence, 'text_pred_tags')
            with tf.variable_scope('pred_tags'):
                text_tags = tf.map_fn(
                    lambda (seq, length): tf.cast(tf.equal(seq[length], 1), tf.float32),
                    (self.viterbi_sequence, text_length),
                    dtype=tf.float32,
                )
                text_tags = tf.expand_dims(text_tags, axis=1)

        feature_columns = [
            tf.feature_column.numeric_column(key='margin', shape=[4]),
            tf.feature_column.numeric_column(key='indent', shape=[4]),
            tf.feature_column.numeric_column(key='font_size', shape=[2]),
        ]
        with tf.name_scope('input_layer'):
            input_layer = tf.feature_column.input_layer(features, feature_columns)
        # net = tf.concat([input_layer, rnn], axis=1)
        # net = rnn
        with slim.arg_scope([slim.fully_connected],
                            weights_regularizer=layers.l2_regularizer(0.0005),
                            normalizer_fn=slim.batch_norm,
                            normalizer_params={'is_training': is_training}):
            net = tf.concat([input_layer, text_tags], axis=1)
            net = slim.fully_connected(net, 256, activation_fn=tf.nn.relu)
            net = slim.dropout(net, keep_prob=Config.model.keep_prob,is_training=is_training)
            net = slim.fully_connected(net, 128, activation_fn=tf.nn.relu)
            net = slim.dropout(net, keep_prob=Config.model.keep_prob,is_training=is_training)
            net = slim.fully_connected(net, 64, activation_fn=tf.nn.relu)
            net = slim.dropout(net, keep_prob=Config.model.keep_prob,is_training=is_training)
            # logits = slim.fully_connected(net, num_classes, activation_fn=None)
            logits = slim.fully_connected(net, 1, activation_fn=None)
            logits = tf.squeeze(logits, 1)
        end_points = {
            'Logits': logits,
            # 'Predictions': tf.nn.softmax(logits, name='Predictions'),
            'Predictions': tf.sigmoid(logits, name='Predictions'),
        }
        return logits, end_points

    def _build_loss(self, logits):
        # self.loss = tf.losses.sparse_softmax_cross_entropy(
        #     self.targets,
        #     logits,
        #     scope="loss")
        self.loss = tf.nn.sigmoid_cross_entropy_with_logits(
            labels=tf.cast(self.targets, tf.float32),
            logits=logits,
            name="loss")
        self.loss = tf.reduce_mean(self.loss)

        log_likelihood, _ = tf.contrib.crf.crf_log_likelihood(self.text_logits, self.tags,
                                                                   self.seq_length, transition_params=self.trans_params)
        self.text_loss = tf.reduce_mean(-log_likelihood, name='text_loss')
        self.total_loss = tf.add(self.loss, self.text_loss, name="total_loss")

    def _build_prediction(self, end_points):
        self.scores = end_points['Predictions']
        # self.predictions = tf.argmax(self.scores, axis=1)
        self.predictions = tf.cast(tf.greater(self.scores, 0.5), tf.int64)

    def _build_optimizer(self):
        self.train_op = layers.optimize_loss(
            self.total_loss, tf.train.get_global_step(),
            optimizer='Adam',
            learning_rate=Config.train.learning_rate,
            summaries=['loss', 'learning_rate', 'gradients', 'gradient_norm', 'global_gradient_norm'],
            name="train_op")

    def _build_metric(self):
        self.metrics = {
            "accuracy": tf.metrics.accuracy(self.targets, self.predictions),
            "text_accuracy": tf.metrics.accuracy(self.viterbi_sequence, self.tags),
        }
