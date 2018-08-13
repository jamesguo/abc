# -*- coding: utf-8 -*-

import os
import time
import numpy as np
import tensorflow as tf
from tensorflow.contrib import rnn
from hbconfig import Config
from tensorflow.contrib import layers

slim = tf.contrib.slim

class CRNN(object):
    def __init__(self,inputs,seq_len,is_training):
        self.is_training = is_training;
        self.inputs = inputs
        self.seq_len = seq_len

    def crnn(self):
        def BidirectionnalRNN(inputs, seq_len):
            """
                Bidirectionnal LSTM Recurrent Neural Network part
            """
            with tf.variable_scope(None, default_name="bidirectional-rnn-1"):
                # Forward
                lstm_fw_cell_1 = rnn.BasicLSTMCell(Config.model.hidden_size)
                # Backward
                lstm_bw_cell_1 = rnn.BasicLSTMCell(Config.model.hidden_size)

                inter_output, _ = tf.nn.bidirectional_dynamic_rnn(lstm_fw_cell_1, lstm_bw_cell_1, inputs, seq_len, dtype=tf.float32)

                outputs = tf.concat(inter_output, 2)

            return outputs

        def CNN(inputs):
            """
                Convolutionnal Neural Network part
            """
            with slim.arg_scope([slim.conv2d, slim.fully_connected],
                                weights_regularizer=layers.l2_regularizer(0.0005),
                                normalizer_fn=slim.batch_norm,
                                normalizer_params={'is_training': self.is_training}):
                conv1 = slim.conv2d(inputs, 64, [1, 1], 1, padding='SAME', scope='conv1')
                conv2 = slim.conv2d(inputs, 64, [2, 1], 1, padding='SAME', scope='conv2')
                conv3 = slim.conv2d(inputs, 64, [4, 1], 1, padding='SAME', scope='conv3')
                conv4 = slim.conv2d(inputs, 64, [8, 1], stride=1, padding='SAME', scope='conv4')
                output = tf.concat([conv1, conv2, conv3, conv4], axis=-1)
                output = tf.reshape(output, [-1, self.nstep, 64*4*Config.model.vector_lenth])
            return output

        #本函数之所以池化的步长为1,是因为output的得到必须有一个维度的长度是可以固定的,但是在池化的过重中,如果nstep
        #是奇数,那么池化就是(n+1)/2,偶数就是(n)/2,那样一来结果的最终维度就无法确定
        def CNN2(inputs):
            # 64 / 3 x 3 / 1 / 1
            conv1 = tf.layers.conv2d(inputs=inputs, filters = 64, kernel_size = (1, 1), padding = "same", activation=tf.nn.relu)
            # 2 x 2 / 1
            pool1 = tf.layers.max_pooling2d(inputs=conv1, pool_size=[2, 2], strides=1, padding="same")
            # 128 / 3 x 3 / 1 / 1
            conv2 = tf.layers.conv2d(inputs=pool1, filters = 128, kernel_size = (2, 1), padding = "same", activation=tf.nn.relu)
            # 2 x 2 / 1
            pool2 = tf.layers.max_pooling2d(inputs=conv2, pool_size=[2, 2], strides=1, padding="same")
            # 256 / 3 x 3 / 1 / 1
            conv3 = tf.layers.conv2d(inputs=pool2, filters = 256, kernel_size = (4, 1), padding = "same", activation=tf.nn.relu)
            # Batch normalization layer
            bnorm1 = tf.layers.batch_normalization(conv3)
            output = tf.reshape(bnorm1, [-1, self.nstep, 256*Config.model.vector_lenth])
            return output

        self.nstep =tf.shape(self.inputs)[1]
        self.batch_size = tf.shape(self.inputs)[0]

        cnn_output = CNN2(self.inputs)

        crnn_model = BidirectionnalRNN(cnn_output, self.seq_len)

        logits = tf.reshape(crnn_model, [-1, Config.model.hidden_size*2])

        W = tf.Variable(tf.truncated_normal([Config.model.hidden_size*2,  Config.data.num_classes], stddev=0.1), name="W")
        b = tf.Variable(tf.constant(0., shape=[Config.data.num_classes]), name="b")

        logits = tf.matmul(logits, W) + b

        logits = tf.reshape(logits, [-1, self.nstep, Config.data.num_classes])

        # Final layer, the output of the BLSTM
        return logits





