#-- coding: utf-8 -*-

import argparse
import atexit
import logging

from hbconfig import Config
import tensorflow as tf

import data_loader
import hook
from model import Model
from tensorflow.contrib.learn.python.learn.utils import saved_model_export_utils


def experiment_fn(run_config, params):

    if Config.model.type == 'ocr':
        from ocr import ocr_model
        model = ocr_model.Model()
    elif Config.model.type == 'text':
        model = Model()
    elif Config.model.type == 'text-dnn':
        from textdnn import text_dnn_model
        model = text_dnn_model.Model()
    elif Config.model.type == 'line-crf':
        from linecrf import crf_model
        model = crf_model.Model()
    elif Config.model.type == 'cell-merge':
        from cellMerge import cell_model
        model = cell_model.Model()
    else:
        raise KeyError('Unknown model type %s' % Config.model.type)

    estimator = tf.estimator.Estimator(
            model_fn=model.model_fn,
            model_dir=Config.train.model_dir,
            params=params,
            config=run_config)

    vocab = data_loader.load_vocab()
    Config.data.vocab_size = len(vocab)
    print("vocab_size: %d" % Config.data.vocab_size)

    train_input_fn, train_input_hook, test_input_fn, test_input_hook = data_loader.make_train_and_test_input_fn()

    train_hooks = []
    if train_input_hook is not None:
        train_hooks.append(train_input_hook)

    if Config.train.print_verbose and Config.model.type == 'text':
        train_hooks.append(hook.print_variables(
            variables=['train/input_0'],
            rev_vocab=vocab,
            every_n_iter=Config.train.check_hook_n_iter))
        train_hooks.append(hook.print_target(
            variables=['train/target_0', 'train/pred_0'],
            rev_vocab=vocab,
            every_n_iter=Config.train.check_hook_n_iter))

    if Config.train.print_verbose and Config.model.type == 'text-dnn':
        train_hooks.append(hook.print_variables(
            variables=['pre_processing/total_text_ids'],
            rev_vocab=vocab,
            every_n_iter=Config.train.check_hook_n_iter
        ))
        train_hooks.append(hook.print_variables(
            variables=['pre_processing/tags', 'text_graph/crf/crf_decode/text_pred_tags'],
            every_n_iter=Config.train.check_hook_n_iter))

    if Config.train.debug:
        from tensorflow.python import debug as tf_debug
        train_hooks.append(tf_debug.LocalCLIDebugHook(ui_type='readline'))

    eval_hooks = []
    if test_input_hook is not None:
        eval_hooks.append(test_input_hook)

    def serving_input_fn():
        if Config.model.type == 'text':
            inputs = {
                "input_data": tf.placeholder(
                    tf.int32, [None, Config.data.max_seq_length], name="input_data")
            }
            return tf.estimator.export.ServingInputReceiver(inputs, inputs)
        elif Config.model.type == 'ocr':

            def _preprocess_image(image_bytes):

                """Preprocess a single raw image."""
                image = tf.image.decode_image(tf.reshape(image_bytes, shape=[]), channels=3)
                image.set_shape([None, None, None])
                image = tf.image.convert_image_dtype(image, dtype=tf.float32)
                image = tf.image.resize_images(image, (64, 64))
                image = tf.image.rgb_to_grayscale(image)
                return image

            use_uint8 = tf.placeholder(dtype=tf.bool, name='use_uint8')
            image_bytes_list = tf.placeholder(
                shape=[None],
                dtype=tf.string,
                name='image_bytes',
            )
            uint8_images = tf.placeholder(
                shape=[None, 64, 64, 1],
                dtype=tf.uint8,
                name='uint8_images',
            )

            def preprocess_uint8_image():
                image = tf.image.convert_image_dtype(uint8_images, dtype=tf.float32)
                return image

            def preprocess_image():
                return tf.map_fn(_preprocess_image, image_bytes_list, back_prop=False, dtype=tf.float32)

            features = {
                'image': tf.cond(use_uint8,
                                 preprocess_uint8_image,
                                 preprocess_image,
                                 )
            }
            receiver_tensors = {'image_bytes': image_bytes_list}

            return tf.estimator.export.ServingInputReceiver(features, receiver_tensors)
        elif Config.model.type == 'text-dnn':
            input_dict = {
                'margin': tf.placeholder(tf.float32, shape=[None, 4], name='margin'),
                'indent': tf.placeholder(tf.float32, shape=[None, 4], name='indent'),
                'font_size': tf.placeholder(tf.float32, shape=[None, 2], name='font_size'),
                'text': tf.placeholder(tf.string, shape=[None,], name='text'),
                'text_length': tf.placeholder(tf.int32, shape=[None,], name='text_length'),
                'next_text': tf.placeholder(tf.string, shape=[None,], name='next_text'),
                'next_text_length': tf.placeholder(tf.int32, shape=[None,], name='next_text_length'),
            }
            return tf.estimator.export.ServingInputReceiver(
                features=input_dict,
                receiver_tensors=input_dict)
        elif Config.model.type == 'line-crf':
            from linecrf import line_example_decoder
            example = tf.placeholder(dtype=tf.string, shape=[], name='serialized_example')
            input_dict, _ = line_example_decoder.parse_tfexample_fn(example, tf.estimator.ModeKeys.PREDICT)
            for key in input_dict:
                input_dict[key] = tf.expand_dims(input_dict[key], 0)
            return tf.estimator.export.ServingInputReceiver(
                features=input_dict,
                receiver_tensors={'serialized_example': example})
        elif Config.model.type == 'cell-merge':  # 此处是为导出模型的时候使用,features就是模型中的features
            input_dict = {
                'x_l': tf.placeholder(tf.string, shape=[None], name='x_l'),
                'x_r': tf.placeholder(tf.string, shape=[None], name='x_r'),
                'l': tf.placeholder(tf.int64, shape=[None], name='l'),
            }
            return tf.estimator.export.ServingInputReceiver(
                features=input_dict,
                receiver_tensors=input_dict)
        else:
            return None

    experiment = tf.contrib.learn.Experiment(
        estimator=estimator,
        train_input_fn=train_input_fn,
        eval_input_fn=test_input_fn,
        train_steps=Config.train.train_steps,
        min_eval_frequency=Config.train.min_eval_frequency,
        eval_steps=Config.train.eval_steps,
        train_monitors=train_hooks,
        eval_hooks=eval_hooks,
        export_strategies=[saved_model_export_utils.make_export_strategy(
            serving_input_fn,
            default_output_alternative_key=None,
            exports_to_keep=1
        )],
    )
    return experiment


def main(mode):
    params = tf.contrib.training.HParams(**Config.model.to_dict())


    run_config = tf.contrib.learn.RunConfig(
            keep_checkpoint_max=20,
            model_dir=Config.train.model_dir,
            save_checkpoints_steps=Config.train.save_checkpoints_steps)

    tf.contrib.learn.learn_runner.run(
        experiment_fn=experiment_fn,
        run_config=run_config,
        schedule=mode,
        hparams=params
    )


if __name__ == '__main__':

    parser = argparse.ArgumentParser(
                        formatter_class=argparse.ArgumentDefaultsHelpFormatter)
    parser.add_argument('--config', type=str, default='text',
                        help='config file name')
    parser.add_argument('--mode', type=str, default='train_and_evaluate',
                        help='Mode (train/test/train_and_evaluate)')
    args = parser.parse_args()

    tf.logging.set_verbosity(logging.DEBUG)

    # Print Config setting
    Config(args.config)
    print("Config: ", Config)
    if Config.get("description", None):
        print("Config Description")
        for key, value in Config.description.items():
            print(" - %s: %s" % (key, value))

    main(args.mode)
