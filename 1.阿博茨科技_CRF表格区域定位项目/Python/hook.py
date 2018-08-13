
from hbconfig import Config
import numpy as np
import tensorflow as tf
from data_loader import ids_to_str


def print_variables(variables, rev_vocab=None, every_n_iter=100):

    return tf.train.LoggingTensorHook(
        variables,
        every_n_iter=every_n_iter,
        formatter=format_variable(variables, rev_vocab=rev_vocab))


def format_variable(keys, rev_vocab=None):

    def to_str(sequence):
        if type(sequence) == np.ndarray:
            if len(sequence.shape) > 1:
                sequence = sequence[0]
            return ids_to_str(rev_vocab, list(sequence))
        else:
            x = int(sequence)
            return rev_vocab.reverse(x)

    def format(values):
        result = []
        for key in keys:
            if rev_vocab is None:
                v = values[key]
                if type(v) == np.ndarray:
                    if len(v.shape) > 1:
                        v = v[0]
                    result.append("{%s = \n%s" % (key, np.array2string(v)))
                else:
                    result.append("{%s = %d" % (key, v))
            else:
                result.append("%s = %s" % (key, to_str(values[key])))

        try:
            return '\n - '.join(result)
        except:
            pass

    return format


def print_target(variables, rev_vocab=None, every_n_iter=100):

    return tf.train.LoggingTensorHook(
        variables,
        every_n_iter=every_n_iter,
        formatter=print_label(variables, rev_vocab=rev_vocab))


def print_label(keys, rev_vocab=None):

    def format(values):
        result = []
        for key in keys:
            if type(values[key]) == np.ndarray:
                value = np.argmax(values[key])
            else:
                value = values[key]
            result.append("%s = %d" % (key, value))

        try:
            return ', '.join(result)
        except:
            pass

    return format
