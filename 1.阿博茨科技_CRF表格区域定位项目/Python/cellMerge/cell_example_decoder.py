# -*- coding: utf-8 -*-
"""
  Created by dhu on 2018/5/3.
"""
import os
import tensorflow as tf
from hbconfig import Config
from tensorflow.python.framework import sparse_tensor

slim_example_decoder = tf.contrib.slim.tfexample_decoder


class CellExampleDecoder(object):
    """Tensorflow Example proto decoder."""

    def __init__(self):
        self.keys_to_features = {
            'x_l': tf.FixedLenFeature([], tf.string),
            'x_r': tf.FixedLenFeature([], tf.string),
            'y': tf.FixedLenFeature([], tf.int64),
            'l': tf.FixedLenFeature([], tf.int64)
        }
        self.items_to_handlers = {
            'x_l': slim_example_decoder.Tensor('x_l'),
            'x_r': slim_example_decoder.Tensor('x_r'),
            'y': slim_example_decoder.Tensor('y'),
            'l': slim_example_decoder.Tensor('l'),
        }

    def decode(self, tf_example_string_tensor):
        serialized_example = tf.reshape(tf_example_string_tensor, shape=[])
        decoder = slim_example_decoder.TFExampleDecoder(self.keys_to_features, self.items_to_handlers)
        keys = decoder.list_items()
        tensors = decoder.decode(serialized_example, items=keys)
        tensor_dict = dict(zip(keys, tensors))
        return tensor_dict


def parse_tfexample_fn(example, mode=tf.estimator.ModeKeys.TRAIN):
    decoder = CellExampleDecoder()
    input_dict = decoder.decode(example)
    if mode != tf.estimator.ModeKeys.PREDICT:
        label = input_dict.pop('y')
    else:
        label = None
    return input_dict, label



if __name__=="__main__":
    import sys
    dataset = tf.data.TFRecordDataset("/home/jhqiu/git/paragraph_classfication/data/cell-eval-2.tfrecord")
    dataset = dataset.repeat(1)
    dataset = dataset.map(parse_tfexample_fn)

    # Our inputs are variable length, so pad them.
    dataset = dataset.batch(64)
    iterator = dataset.make_one_shot_iterator()
    features, labels = iterator.get_next()
    labels = tf.cast(labels, tf.int32)
    with tf.Session() as sess:
        batch_x =tf.string_join([features["x_l"], features["x_r"]], " <a> ", name='total_text')
        sequence_lengths = features["l"]
        batch_y = labels
        keep_prob =1.0
        table = tf.contrib.lookup.index_table_from_file(
            vocabulary_file=os.path.join('../data', 'vocab.txt'),
            default_value=0
        )
        words = tf.string_split(batch_x, delimiter=" ")#此接口会产生一个索引index,和值value
        #此处会自动对齐
        words_dense=tf.sparse_tensor_to_dense(words,default_value='<PAD>')#根据value和index生成一个矩阵,不在index里的位置补0
        words_id=table.lookup(words_dense)#将原本的value变成id
        batch_x_input = tf.cast(words_id, tf.int32)
        tf.tables_initializer().run()
        tf.global_variables_initializer().run()

        for i in range(10000):
            #acc, loss_ = sess.run([accuracy, loss])
            #print("Step %d, loss: %.5f, accuracy: %.5f" % (i, loss_, acc))
            t1,t2,t3=sess.run([batch_x,batch_x_input,sequence_lengths])
            for m1,m2,m3 in zip(t1,t2,t3):
                print (m1)
                print (m2)
                print (m3)



