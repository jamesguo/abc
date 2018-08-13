# -*- coding: utf-8 -*-
"""
  Created by dhu on 2018/5/3.
"""
import os
import numpy as np
import tensorflow as tf
from tensorflow.contrib import layers
from tensorflow.contrib.rnn import LSTMCell
from gensim.models import Word2Vec
from hbconfig import Config
from data_loader import load_vocab
slim = tf.contrib.slim

UNK = 0
PAD = 1


class Model:

    def __init__(self):
        pass

    def model_fn(self, mode, features, labels, params):
        self.mode = mode
        self.is_training = self.mode == tf.estimator.ModeKeys.TRAIN
        self.batch_size = params.batch_size
        self.params = params

        self.loss, self.train_op, self.metrics = None, None, None
        self.add_placeholders(features,labels)
        self.build_graph()
        # train mode: required loss and train_op
        # eval mode: required loss
        # predict mode: required predictions
        scaffold = tf.train.Scaffold(
            ready_op=tf.tables_initializer(),
        )
        export_outputs = {
            "predict": tf.estimator.export.PredictOutput(
                {
                    "scores": tf.identity(self.scores, "scores"),
                    "class": tf.identity(self.predictions, "class"),
                })
        }

        return tf.estimator.EstimatorSpec(
            mode=mode,
            loss=self.loss,
            train_op=self.train_op,
            scaffold=scaffold,
            eval_metric_ops=self.metrics,
            predictions={"prediction": self.predictions},
            export_outputs=export_outputs)

    #建立graph
    def build_graph(self):
        self.buildInputVector()
        self._build_net()
        if self.mode != tf.estimator.ModeKeys.PREDICT:
            self._build_loss()
            self._build_optimizer()
            self._build_metric()


    def add_placeholders(self,features,labels):
        #此处使用的连接符是" <a> ",是为了后续分割的时候<a>会被分割为一个单独的向量
        self.batch_x =tf.string_join([features["x_l"], features["x_r"]], " <a> ", name='total_text')
        self.sequence_lengths = features["l"]
        self.batch_y = labels
        if self.is_training:
            self.keep_prob =Config.model.keep_prob
        else:
            self.keep_prob=1.0

    def buildInputVector(self):
        self.table = tf.contrib.lookup.index_table_from_file(
            vocabulary_file=os.path.join(Config.data.base_path, 'vocab.txt'),
            default_value=UNK
        )
        words = tf.string_split(self.batch_x, delimiter=" ")#此接口会产生一个索引index,和值value
        #此处会自动对齐
        words_dense=tf.sparse_tensor_to_dense(words,default_value='<PAD>')#根据value和index生成一个矩阵,不在index里的位置补0
        words_id=self.table.lookup(words_dense)#将原本的value变成id
        self.batch_x_input = tf.cast(words_id, tf.int32)

    def _build_metric(self):
        self.metrics = {
            "accuracy": tf.metrics.accuracy(self.predictions, self.batch_y),
        }

    def _build_loss(self):
        # CalculateMean cross-entropy loss
        with tf.name_scope("loss"):
            losses = tf.nn.softmax_cross_entropy_with_logits(logits=self.logits, labels=tf.one_hot(self.batch_y,2))
            self.loss = tf.reduce_mean(losses)

    def _build_optimizer(self):
        #输出的接口
        with tf.variable_scope("train_step"):
            self.train_op = layers.optimize_loss(
                self.loss, tf.train.get_global_step(),
                optimizer='Adam',
                learning_rate=Config.train.learning_rate,
                summaries=['loss', 'learning_rate', 'gradients', 'gradient_norm', 'global_gradient_norm'],
                name="train_op")
    def _build_net(self):
        #词嵌入
        embedding = tf.get_variable(
            "embedding", [Config.data.vocab_size, Config.model.embed_dim], dtype=tf.float32)

        self.word_embeddings = tf.nn.embedding_lookup(params=embedding,
                                                      ids=self.batch_x_input,
                                                      name="word_embeddings")

        cell1 = tf.contrib.rnn.DropoutWrapper(LSTMCell(Config.model.hidden_size), output_keep_prob=self.keep_prob)
        cell2 = tf.contrib.rnn.DropoutWrapper(LSTMCell(Config.model.hidden_size), output_keep_prob=self.keep_prob)
        with tf.variable_scope("bi-lstm"):
            _,(output_state_fw, output_state_bw)  = tf.nn.bidirectional_dynamic_rnn(
                cell_fw=cell1,
                cell_bw=cell2,
                inputs=self.word_embeddings,
                sequence_length=self.sequence_lengths,
                dtype=tf.float32)
        #output_states为(output_state_fw, output_state_bw)，包含了前向和后向最后的隐藏状态的组成的元组state。
        #state由（c，h）组成，分别代表memory cell和hidden state,因此维度是Config.model.hidden_size*4
        output = tf.concat([output_state_fw[0],output_state_fw[1],output_state_bw[0],output_state_bw[1]], axis=1)
        output = tf.reshape(output, [-1, Config.model.hidden_size*4])
        with slim.arg_scope([slim.fully_connected],
                            weights_regularizer=layers.l2_regularizer(0.0005),
                            normalizer_fn=slim.batch_norm,
                            normalizer_params={'is_training': self.is_training}):
            net = slim.fully_connected(output, 64, activation_fn=tf.nn.relu)
            net = slim.dropout(net, keep_prob=self.keep_prob,is_training=self.is_training)
            net = slim.fully_connected(output, 2,activation_fn=None)

        with tf.variable_scope("output"):
            self.logits = net
            #这样方便在Java里直接取到值
            self.scores=tf.reduce_max(tf.nn.softmax(self.logits),axis=1,name="scores")
            self.predictions = tf.argmax(self.logits, 1, name="predictions")
