# -*- coding: utf-8 -*-
"""
  Created by dhu on 2018/3/9.
"""

from __future__ import print_function, unicode_literals

import tensorflow as tf
from tensorflow.contrib.learn.python.learn.preprocessing import CategoricalVocabulary, VocabularyProcessor
import numpy as np
from hbconfig import Config
import os
import functools

EOS = u"<EOS>"
EOL = u"<EOL>"
PAD_ID = 1
EOS_ID = 2
EOL_ID = 3


class IteratorInitializerHook(tf.train.SessionRunHook):
    """Hook to initialise data iterator after Session is created."""

    def __init__(self):
        super(IteratorInitializerHook, self).__init__()
        self.iterator_initializer_func = None

    def after_create_session(self, session, coord):
        """Initialise the iterator after the session has been created."""
        if self.iterator_initializer_func is not None:
            self.iterator_initializer_func(session)


def load_vocab():
    vocab = CategoricalVocabulary()
    with tf.gfile.GFile(os.path.join(Config.data.base_path, 'vocab.txt'), "r") as f:
        lines = f.readlines()
        for line in lines:
            vocab.add(line.strip().decode("utf-8"))
    vocab.freeze()
    return vocab


def read_file():
    vocab = load_vocab()
    with tf.gfile.GFile(Config.data.raw_data_path, "r") as f:
        lines = f.readlines()
        data = np.array([], dtype=np.int64)
        for line in lines:
            if len(line.strip()) <= 5:
                continue
            line = line.strip().decode("utf-8")
            ids = [vocab.get(ch) for ch in line]  # 0为PAD_ID
            ids.append(EOS_ID)
            data = np.append(data, np.array(ids))
        np.save(Config.data.processed_path, data)


def read_data(data, batch_size, max_len):
    while True:
        start = np.random.randint(len(data)-max_len, size=batch_size)
        length = np.random.randint(9, high=max_len, size=batch_size)
        batch_data = np.ones(dtype=np.int32, shape=(batch_size, max_len))
        batch_data = batch_data * PAD_ID
        batch_label = np.zeros(dtype=np.int32, shape=(batch_size, Config.data.num_classes))
        for i in range(batch_size):
            sub_data = np.copy(data[start[i]:start[i]+length[i]])
            w = np.argwhere(sub_data == EOS_ID)
            if len(w) > 0:
                if len(w) > 1:
                    sub_data = sub_data[:w[1, 0]]
                sub_data[w[0]] = EOL_ID
                batch_data[i, 0:len(sub_data)] = sub_data
                batch_label[i] = [1, 0]
            else:
                index = np.random.randint(4, high=len(sub_data))
                sub_data = np.insert(sub_data, index, EOL_ID)
                batch_data[i, 0:len(sub_data)] = sub_data
                batch_label[i] = [0, 1]
        yield batch_data, batch_label


def _parse_text_tfexample_fn(example, mode):
    from textdnn import tf_example_decoder
    decoder = tf_example_decoder.TfExampleDecoder()
    input_dict = decoder.decode(example)
    label = input_dict.pop('can_merge')
    return input_dict, label


def build_text_vector(features, labels, table):
    text = tf.string_split(features['text'], delimiter=" ")
    words_dense = tf.sparse_tensor_to_dense(text, default_value='<PAD>')  # 根据value和index生成一个矩阵,不在index里的位置补0
    words_id = table.lookup(words_dense)  # 将原本的value变成id
    words_id = tf.cast(words_id, tf.float32)
    words_id = tf.reshape(words_id, [-1, 100])
    features['text'] = words_id
    return features, labels


def get_input_fn(mode, tfrecord_pattern, batch_size, parse_tfexample_fn, input_hook=None):
    """Creates an input_fn that stores all the data in memory.

    Args:
     mode: one of tf.contrib.learn.ModeKeys.{TRAIN, INFER, EVAL}
     tfrecord_pattern: path to a TF record file created using create_dataset.py.
     batch_size: the batch size to output.

    Returns:
      A valid input_fn for the model estimator.
    """
    def _input_fn():
        """Estimator `input_fn`.

        Returns:
          A tuple of:
          - Dictionary of string feature name to `Tensor`.
          - `Tensor` of target labels.
        """
        # table = tf.contrib.lookup.index_table_from_file(
        #     vocabulary_file=os.path.join(Config.data.base_path, 'vocab.txt'),
        #     num_oov_buckets=1)

        dataset = tf.data.TFRecordDataset.list_files(tfrecord_pattern)
        if mode == tf.estimator.ModeKeys.TRAIN:
            dataset = dataset.shuffle(buffer_size=100)
        dataset = dataset.repeat()
        # Preprocesses 10 files concurrently and interleaves records from each file.
        dataset = dataset.interleave(
            tf.data.TFRecordDataset,
            cycle_length=10,
            block_length=1)
        dataset = dataset.map(
            functools.partial(parse_tfexample_fn, mode=mode),
            num_parallel_calls=10)

        if Config.data.type == 'tfrecord':
            from tensorflow.python.ops import lookup_ops
            vocab_file = os.path.join(Config.data.base_path, 'vocab.txt')

            table = lookup_ops.index_table_from_file(
                vocabulary_file=vocab_file,
                default_value=0
            )

            def filter_text_fn(features, labels):
                return tf.greater(table.lookup(labels), 0)

            # 只取在字典里的训练数据
            dataset = dataset.filter(filter_text_fn)

        if Config.data.type == 'line-tfrecord':
            from tensorflow.python.ops import lookup_ops
            vocab_file = os.path.join(Config.data.base_path, 'vocab.txt')

            table = lookup_ops.index_table_from_file(
                vocabulary_file=vocab_file,
                default_value=0
            )

            def line_filter_fn(features, labels):
                return features['line_count'] > 0

            dataset = dataset.filter(line_filter_fn)
            if Config.train.useText:
                dataset = dataset.map(lambda features, labels: build_text_vector(features, labels, table))

        #测试数据较多的时候,为保证测试结果较为均衡，也应该予以shuffle
        if mode != tf.estimator.ModeKeys.PREDICT:
            dataset = dataset.shuffle(buffer_size=50000)

        # Our inputs are variable length, so pad them.
        if Config.data.type == 'cell-tfrecord':
            dataset = dataset.batch(batch_size)
        else:
            dataset = dataset.padded_batch(
                batch_size, padded_shapes=dataset.output_shapes)
        # iterator = dataset.make_one_shot_iterator()
        '''
        iterator = dataset.make_initializable_iterator()
        input_hook.iterator_initializer_func = lambda sess: {
            sess.run(iterator.initializer)
        }
        features, labels = iterator.get_next()
        '''
        return dataset

    return _input_fn


def make_train_and_test_input_fn_from_tfrecord():
    if Config.data.type == 'tfrecord':
        from ocr import font_example_decoder
        parse_tfexample_fn = font_example_decoder.parse_tfexample_fn
    elif Config.data.type == 'text-tfrecord':
        parse_tfexample_fn = _parse_text_tfexample_fn
    elif Config.data.type == 'line-tfrecord':
        from linecrf import line_example_decoder
        parse_tfexample_fn = line_example_decoder.parse_tfexample_fn
    elif Config.data.type == 'cell-tfrecord':
        from cellMerge import cell_example_decoder
        parse_tfexample_fn = cell_example_decoder.parse_tfexample_fn
    else:
        parse_tfexample_fn = None
    train_input_hook = IteratorInitializerHook()
    train_input_fn = get_input_fn(tf.estimator.ModeKeys.TRAIN,
                                  os.path.join(Config.data.base_path, Config.data.train_data_pattern),
                                  Config.model.batch_size,
                                  parse_tfexample_fn,
                                  input_hook=train_input_hook)

    test_input_hook = IteratorInitializerHook()
    test_input_fn = get_input_fn(tf.estimator.ModeKeys.EVAL,
                                 os.path.join(Config.data.base_path, Config.data.eval_data_pattern),
                                 Config.model.batch_size,
                                 parse_tfexample_fn,
                                 input_hook=test_input_hook)

    return train_input_fn, train_input_hook, test_input_fn, test_input_hook


def make_train_and_test_set():
    data = np.load(Config.data.processed_path)
    return data[0:-5000], data[-5000:]


def make_train_and_test_input_fn():
    if 'tfrecord' in Config.data.type:
        return make_train_and_test_input_fn_from_tfrecord()

    train_data, test_data = make_train_and_test_set()
    train_input_fn, train_input_hook = make_batch(train_data,
                                                  batch_size=Config.model.batch_size,
                                                  scope="train")
    test_input_fn, test_input_hook = make_batch(test_data,
                                                batch_size=Config.model.batch_size,
                                                scope="test")
    return train_input_fn, train_input_hook, test_input_fn, test_input_hook


def make_batch(data, batch_size=64, scope="train"):

    def get_inputs():

        iterator_initializer_hook = IteratorInitializerHook()

        def train_inputs():
            with tf.name_scope(scope):

                if scope == "train":
                    x = np.ones(dtype=np.int32, shape=(Config.data.trainset_size, batch_size, Config.data.max_seq_length))
                    y = np.zeros(dtype=np.int32, shape=(Config.data.trainset_size, batch_size, Config.data.num_classes))
                else:
                    x = np.ones(dtype=np.int32, shape=(Config.data.testset_size, batch_size, Config.data.max_seq_length))
                    y = np.zeros(dtype=np.int32, shape=(Config.data.testset_size, batch_size, Config.data.num_classes))

                x = x * PAD_ID
                for i in range(len(x)):
                    x[i], y[i] = next(read_data(data, batch_size, Config.data.max_seq_length))

                x = x.reshape([-1, Config.data.max_seq_length])
                y = y.reshape([-1, Config.data.num_classes])

                # Define placeholders
                input_placeholder = tf.placeholder(
                    tf.int32, [None, Config.data.max_seq_length], name='input_placeholder')
                output_placeholder = tf.placeholder(
                    tf.int32, [None, Config.data.num_classes], name='output_placeholder')

                # Build dataset iterator
                dataset = tf.data.Dataset.from_tensor_slices(
                    (input_placeholder, output_placeholder))

                if scope == "train":
                    dataset = dataset.repeat(None)  # Infinite iterations
                else:
                    dataset = dataset.repeat(1)  # 1 Epoch
                # dataset = dataset.shuffle(buffer_size=buffer_size)
                dataset = dataset.batch(batch_size)

                iterator = dataset.make_initializable_iterator()
                next_x, next_y = iterator.get_next()

                tf.identity(next_x[0], 'input_0')
                tf.identity(next_y[0], 'target_0')

                # Set runhook to initialize iterator
                iterator_initializer_hook.iterator_initializer_func = \
                    lambda sess: sess.run(
                        iterator.initializer,
                        feed_dict={input_placeholder: x,
                                   output_placeholder: y})

                # Return batched (features, labels)
                return next_x, next_y

        # Return function and hook
        return train_inputs, iterator_initializer_hook

    return get_inputs()


def ids_to_str(vocab, ids):
    return "".join([vocab.reverse(cid) for cid in ids if cid != PAD_ID])


def sentence2id(vocab, sentence):
    return [vocab.get(ch) for ch in sentence]


def _pad_input(input_, size):
    return input_ + [PAD_ID] * (size - len(input_))


if __name__ == "__main__":
    Config('text')
    read_file()
    # exit()
    vocab = load_vocab()
    Config.data.vocab_size = len(vocab)
    train_data, test_data = make_train_and_test_set()
    data, label = next(read_data(train_data, 10, 40))
    for j in range(10):
        print(ids_to_str(vocab, data[j]))
        print(label[j])


