#coding:utf-8
'''
Created on 2017年12月26日

@author: qiujiahao

@email:997018209@qq.com

'''
import argparse

def get_args():
    parser = argparse.ArgumentParser() 
    #http服务的配置
    parser.add_argument('-p', '--http_port', help='server端口号',type=int,default='1234')
    parser.add_argument('--http_host', help='server地址',type=str,default='0.0.0.0')
    
    #模型参数
    parser.add_argument('--embedded_size', help='词嵌入的维度',type=int,default=128)
    parser.add_argument('--dropout', help='dropout',type=float,default=0.5)
    parser.add_argument('--hidden_dim', help='LSTM隐层的大小',type=int,default=128)
    parser.add_argument('-m','--module_path', help='模型存放地址',type=str,default='../data/runs/bilstm_crf')
    parser.add_argument('-m2','--module_java_path', help='java模型存放地址',type=str,default='../data/runs/bilstm_crf_java')
    parser.add_argument('--optimizer', help='优化器',type=str,default='Adam')
    parser.add_argument('--lr', help='优化器',type=float,default='0.0001')
    parser.add_argument('--batch_size', help='batch_size',type=int,default=64)
    parser.add_argument('--num_epochs', help='num_epochs',type=int,default=50)
    parser.add_argument('--print_per_batch', help='print_per_batch',type=int,default=10000)
    parser.add_argument('--file_nums', help='文件切分数量',type=int,default=10)
    parser.add_argument('--test_rate', help='测试数据占比',type=float,default=0.1)
    parser.add_argument('--dev_rate', help='验证数据占比',type=float,default=0.1)
    args = parser.parse_args()
    return args 


    