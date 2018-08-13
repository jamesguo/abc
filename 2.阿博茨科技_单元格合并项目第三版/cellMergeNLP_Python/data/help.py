#coding:utf-8
'''
Created on 2018年3月26日

@author: jhqiu
'''
import os
import jieba
import numpy as np
import tensorflow as tf
from lxml import etree
from collections import defaultdict

class help(object):
    #处理类别二数据:直接从xml中获取内容
    def process_data(self):
        content=set()#去重
        file_data=set()#去重
        table_num=0
        self.files_quests=defaultdict(set)
        with open('origin.txt','w',encoding='utf-8') as f1:
            with open('train.txt','w',encoding='utf-8') as f3: 
                with open('test.txt','w',encoding='utf-8') as f4: 
                    for parent,dirnames,filenames in (os.walk('done')):
                        for file in filenames:
                            path=parent+'/'+file
                            if path.endswith('.html') == False:
                                continue
                            #只要第一个tr里的所有内容和后面所有tr里的第一个
                            with open(path,'r') as f2:
                                temp=''.join(f2.readlines())
                            html=etree.HTML(temp)
                            #print(path)
                            for table in html.xpath('//table'):
                                table_num+=1
                                one_table=set()
                                if 0==np.random.randint(0,20):#随机抽取10%作为测试集合
                                    f=f4
                                else:
                                    f=f3
                                for r in table.xpath('./tr/td/text()'):
                                    line=r.replace(' ','').replace('\t','').replace('\n','').strip()#可能存在制表符和换行符
                                    if self.check_contain_chinese(line):
                                        one_table.add(line)
                                        content.add(line)
                                self.write_data_files(f,one_table,file_data)#在一个table里进行数据组合
                    #按照胡金林的要求,将测试中发现的副样本加入到训练集合中
                    self.addTestResultToTrainData(f3,file_data)
            for w in content:
                f1.write('{}\n'.format(w))
            print('原始短语数量:{}'.format(len(content)))  
            print('原始tabel数量:{}'.format(table_num))

    #将测试中发现的副样本加入到训练集合中
    def addTestResultToTrainData(self,f,file_data):
        one_table=set()
        num=0
        with open('testNegativeSample.txt','r',encoding='utf-8') as f1:
            for line in f1.readlines():
                line=line.strip().replace(" ","")
                if len(line)<=1:
                    continue
                if line.startswith("#"):
                    self.write_data_files(f, one_table, file_data)
                    num += len(one_table)
                    one_table = set()
                else:
                    for cell in line.split("<a>"):
                        one_table.add(cell)
        #到文件末尾
        if len(one_table)!=0:
            self.write_data_files(f, one_table, file_data)
            num += len(one_table)
        print("测试负样本中原始短语的数量:",num)

    def check_contain_chinese(self,check_str,ignore=True):
        if ignore==False:
            if len(check_str)>=50:
                print('长度超长',len(check_str))
                return False
        for ch in check_str:
            if u'\u4e00' <= ch <= u'\u9fff':
                return True
        return False

    def write_data_files(self,f,one_table,file_data):
        quests=list(one_table)
        nums=len(quests)
        
        print('当前table的短语数量是:{}'.format(len(quests)))
        for line in quests: 
            #处理一个短语
            self.process_one_sentence(f,line,file_data,9)
            #如果没有2个以上的短语数量就不需要两两组合了
            if len(quests)<=1:
                continue
            #处理2个短语
            num=0
            while(num<nums/2):
                num+=1
                line2=line
                while(line==line2):
                    line2=np.random.choice(quests)
                self.process_two_sentence(f,line,line2,file_data,9)

    #窗口滑动是为了制造更多的数据,同时在实际使用的时候存在表格被划分成多分的情况
    def process_one_sentence(self,f,quest,file_data,windows_size):
        for i in range(len(quest)):
            sub1=quest[:i]
            sub2=quest[i:]
            sub3=sub1+"<b>"+sub2
            if len(sub1)>=2 and len(sub2)>=2 and True==self.check_contain_chinese(sub3,True) and sub3 not in file_data:
                f.write('{}<a>{}\n'.format(sub3, 1))
                file_data.add(sub3)

        #左右添加'&'是为了窗口滑动的时候能滑动到更小的单元
        quest='&'*(windows_size-2)+quest+'&'*(windows_size-2)
        for i in range(len(quest)):
            j=i+windows_size
            sub=quest[i:j]            
            if len(sub)!=windows_size or False==self.check_contain_chinese(sub,True):
                continue
            sub=sub.replace('&','')
            for i in range(2,len(sub)):
                sub1,sub2=sub[:i],sub[i:] 
                if len(sub1)<2 or len(sub2)<2:
                    continue
                sub3=sub1+'<b>'+sub2
                if sub3 not in file_data:
                    f.write('{}<a>{}\n'.format(sub3,1)) 
                    file_data.add(sub3)         
                                            
    def process_two_sentence(self,f,quest1,quest2,file_data,windows_size):
        sub1 = quest1
        sub2 = quest2
        sub3 = sub1 + "<b>" + sub2
        if len(sub1) >= 2 and len(sub2) >= 2 and True == self.check_contain_chinese(sub3,True) and sub3 not in file_data:
            f.write('{}<a>{}\n'.format(sub3, 0))
            file_data.add(sub3)

        #增加不使用窗口的情况
        for i in range(len(quest1)):
            sub1=quest1[i:]
            if windows_size<=len(sub1):
                continue
            j=windows_size-len(sub1)
            sub2=quest2[0:j]
            sub=sub1+'<b>'+sub2
            if sub in file_data:
                continue
            if(len(sub1)<2 or len(sub2)<2):
                continue   
            if False==self.check_contain_chinese(sub,True):
                continue
            f.write('{}<a>{}\n'.format(sub,0)) 
            file_data.add(sub)    

    def test(self):
        #统计正负样本的数量
        num1=0
        num0=0
        with open('train.txt','r',encoding='utf-8') as f:
            for line in f.readlines():
                line=line.strip()
                if 1==int(line.split('<a>')[1]):
                    num1+=1
                else:
                    num0+=1
        print("0:1:{},total:{}".format(num0/num1,num0+num1))

    def buildWordDict(self):
        print("开始构建字典文件")
        self.word_to_id={}
        self.word_to_id["<UNK>"]=len(self.word_to_id)
        self.word_to_id["<PAD>"] = len(self.word_to_id)
        self.word_to_id["<a>"] = len(self.word_to_id)
        self.word_to_id["<b>"] = len(self.word_to_id)
        self.train_quest_label={}
        self.test_quest_label={}
        with open('../data/train.txt','r',encoding='utf-8') as f:
            for line in f.readlines():
                line=line.strip()
                if len(line)<1:
                    continue
                quest,label=line.split("<a>")
                self.train_quest_label[quest]=label
                for w in line:
                    if w not in self.word_to_id:
                        self.word_to_id[w]=len(self.word_to_id)

        with open('../data/test.txt','r',encoding='utf-8') as f:
            for line in f.readlines():
                line=line.strip()
                if len(line)<1:
                    continue
                quest, label = line.split("<a>")
                self.test_quest_label[quest]=label
                for w in line:
                    if w not in self.word_to_id:
                        self.word_to_id[w]=len(self.word_to_id)
        self.id_to_word={id:w for w,id in self.word_to_id.items()}
        with open("../data/vocab.txt",'w',encoding='utf-8') as f:
            for i in range(len(self.id_to_word)):
                f.write(self.id_to_word[i])
                if i != len(self.id_to_word)-1:
                    f.write("\n")
        print("字典数量:",len(self.id_to_word))
    def write_to_tfRecorf(self):
        print("开始生成TfRecord数据")
        test_writer = tf.python_io.TFRecordWriter('../data/cell-eval-1.tfrecord')
        train_writer = tf.python_io.TFRecordWriter('../data/cell-train-1.tfrecord')
        slice = int(len(self.train_quest_label) / 10)
        quest_labels=list(self.train_quest_label.items())
        for i in range(10):
            temp=np.random.permutation(quest_labels[i*slice:(i+1)*slice])
            for quest,label in temp:
                lquest,rquest=quest.split("<b>")
                lenth=len(lquest)+len(rquest)+1
                quest=list(lquest)+["<a>"]+list(rquest)
                if len(quest)<10:
                    quest+=["<PAD>"]*(10-len(quest))
                #此处必须使用空格作为分隔符，因为tf.string_split是以default_value中的每一个元素作为分割的
                quest=" ".join(quest)
                self.write_tfrecord(train_writer,quest,lenth,label)
        train_writer.close()
        slice = int(len(self.test_quest_label) / 3)
        quest_labels=list(self.test_quest_label.items())
        for i in range(3):
            temp=np.random.permutation(quest_labels[i*slice:(i+1)*slice])
            for quest,label in temp:
                lquest,rquest=quest.split("<b>")
                lenth=len(lquest)+len(rquest)+1
                quest=list(lquest)+["<a>"]+list(rquest)
                if len(quest)<10:
                    quest+=["<PAD>"]*(10-len(quest))
                #此处必须使用空格作为分隔符，因为tf.string_split是以default_value中的每一个元素作为分割的
                quest=" ".join(quest)
                self.write_tfrecord(test_writer,quest,lenth,label)
        test_writer.close()
    def write_tfrecord(self,writer,quest,lenth,label):
        ex = self.make_example(str.encode(quest),int(label),lenth) #y从文件里读出来的时候是字符类型
        writer.write(ex.SerializeToString())

    def make_example(self,x,y,l):
        return tf.train.Example(features=tf.train.Features(feature={
            'x' : tf.train.Feature(bytes_list=tf.train.BytesList(value=[x])),
            'y' : tf.train.Feature(int64_list=tf.train.Int64List(value=[y])),
            'l': tf.train.Feature(int64_list=tf.train.Int64List(value=[l]))
        }))


if __name__=='__main__':
    h=help()
    h.process_data()
    h.test()
    h.buildWordDict()
    h.write_to_tfRecorf()

