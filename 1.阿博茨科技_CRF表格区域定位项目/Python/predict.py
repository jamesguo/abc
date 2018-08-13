
#-*- coding: utf-8 -*-

from __future__ import print_function
import argparse
import os
import sys

from hbconfig import Config
import numpy as np
import tensorflow as tf

import data_loader
from model import Model

session_ = None

def _get_user_input():
    """ Get user's input, which will be transformed into encoder input later """
    print("> ", end="")
    sys.stdout.flush()
    return sys.stdin.readline().strip().decode('utf-8')


def main():
    vocab = data_loader.load_vocab()
    Config.data.vocab_size = len(vocab)

    print("Typing anything :) \n")

    while True:
        sentence_1 = _get_user_input()
        sentence_2 = _get_user_input()

        result = predict_savedmodel_new(sentence_1, sentence_2)
        print(result)
        continue

        ids_1 = data_loader.sentence2id(vocab, sentence_1)
        ids_2 = data_loader.sentence2id(vocab, sentence_2)
        ids = ids_1 + [data_loader.EOL_ID] + ids_2

        if len(ids) > Config.data.max_seq_length:
            start = 0
            if len(ids_1) > Config.data.max_seq_length / 2:
                start = len(ids_1) - Config.data.max_seq_length / 2
            ids = ids[start:start+Config.data.max_seq_length]

        # result = predict(ids)
        result = predict_savedmodel(vocab, ids)
        print(result)


def predict_savedmodel(vocab, ids):
    global session_
    if session_ is None:
        session_ = tf.Session(graph=tf.Graph())
        servo_dir = './logs/text/export/Servo'
        latest_dir = max([os.path.join(servo_dir, d) for d in os.listdir(servo_dir) if os.path.isdir(os.path.join(servo_dir, d))], key=os.path.getmtime)
        tf.saved_model.loader.load(session_, ["serve"], latest_dir)

    X = np.array(data_loader._pad_input(ids, Config.data.max_seq_length), dtype=np.int32)
    X = np.reshape(X, (1, Config.data.max_seq_length))
    # batch = np.ones(shape=(16, 40), dtype=np.int32)
    # batch = batch * data_loader.PAD_ID
    # batch[0] = X

    # input_data = session_.graph.get_tensor_by_name("Placeholder:0")
    # classes_tensor = session_.graph.get_tensor_by_name("class:0")
    # scores_tensor = session_.graph.get_tensor_by_name("scores:0")
    classes, scores = session_.run(["class:0", "scores:0"], feed_dict={
        "input_data:0": X
    })
    # print(data_loader.ids_to_str(vocab, X[0]))
    print(scores[0])
    prediction = classes[0]
    return prediction


def split_text(text):
    chars = [ch.replace(u' ', u'<SPACE>') for ch in text.strip()]
    return len(chars), " ".join(chars)


def predict_savedmodel_new(text, next_text):
    global session_
    if session_ is None:
        session_ = tf.Session(graph=tf.Graph())
        servo_dir = './logs/text-dnn/export/Servo'
        latest_dir = max([os.path.join(servo_dir, d) for d in os.listdir(servo_dir) if os.path.isdir(os.path.join(servo_dir, d))], key=os.path.getmtime)
        tf.saved_model.loader.load(session_, ["serve"], latest_dir)

    text_length, text = split_text(text)
    next_text_length, next_text = split_text(next_text)
    crf_tags = session_.run("crf_tags:0", feed_dict={
        "margin:0": np.array([[0, 0, 0, 0]], dtype=np.float32),
        "indent:0": np.array([[0, 0, 0, 0]], dtype=np.float32),
        "font_size:0": np.array([[9, 9]], dtype=np.float32),
        "text:0": np.array([text]),
        "next_text:0": np.array([next_text]),
        "text_length:0": np.array([text_length], dtype=np.int32),
        "next_text_length:0": np.array([next_text_length], dtype=np.int32),
    })
    # print(data_loader.ids_to_str(vocab, X[0]))
    return crf_tags[0]


def predict_client(ids):
    from grpc.beta import implementations
    from tensorflow_serving.apis import predict_pb2
    from tensorflow_serving.apis import prediction_service_pb2

    X = np.array(data_loader._pad_input(ids, Config.data.max_seq_length), dtype=np.int32)
    X = np.reshape(X, (1, Config.data.max_seq_length))
    channel = implementations.insecure_channel("localhost", 9000)
    stub = prediction_service_pb2.beta_create_PredictionService_stub(channel)
    request = predict_pb2.PredictRequest()
    request.model_spec.name = 'text'
    request.model_spec.signature_name = "predict"
    request.inputs['input_data'].CopyFrom(tf.contrib.util.make_tensor_proto(X))
    result = stub.Predict(request, 10.0)  # 10 secs timeout
    classes = tf.contrib.util.make_ndarray(result.outputs['class'])
    scores = tf.contrib.util.make_ndarray(result.outputs['scores'])
    print(scores[0])
    prediction = classes[0]
    if Config.data.labels is not None:
        prediction = Config.data.labels[prediction]
    return prediction


if __name__ == '__main__':

    parser = argparse.ArgumentParser(
                        formatter_class=argparse.ArgumentDefaultsHelpFormatter)
    parser.add_argument('--config', type=str, default='text',
                        help='config file name')
    args = parser.parse_args()

    Config(args.config)
    Config.model.batch_size = 1

    os.environ['TF_CPP_MIN_LOG_LEVEL'] = '3'
    tf.logging.set_verbosity(tf.logging.ERROR)

    main()
