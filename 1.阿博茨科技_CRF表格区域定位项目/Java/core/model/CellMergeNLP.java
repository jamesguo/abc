package com.abcft.pdfextract.core.model;

import org.apache.commons.lang3.tuple.Pair;
import org.tensorflow.*;
import org.tensorflow.Session.Runner;
import org.apache.commons.lang3.StringUtils;
import java.io.*;
import java.util.*;


public class CellMergeNLP {

    //入参是你想输入的多句话
    public static List<Pair<Float, Long>> cellMergePredict(ArrayList<Pair<String, String>> rowCells) {
        SavedModelBundle savedModelBundle = TensorflowManager.INSTANCE.getSavedModelBundle(TensorflowManager.CELL_MERGE);
        Session tfSession = savedModelBundle.session();
        List<Pair<Float, Long>> result = new ArrayList<>();
        int batch_size = rowCells.size();
        float[] scores = new float[batch_size];
        long[] predicts = new long[batch_size];

        feed_data f = new feed_data();
        f.create_feed_data(rowCells);

        Runner run = tfSession.runner().feed("x_l", f.x_l).feed("x_r", f.x_r).feed("l", f.x_lenth);
        List<Tensor<?>> output = run.fetch("scores").fetch("class").run();

        output.get(0).copyTo(scores);
        output.get(1).copyTo(predicts);

        for (int i = 0; i < batch_size; i++){
            result.add(Pair.of(scores[i], predicts[i]));
        }
        return result;
    }
}

class feed_data {
    Tensor x_l;
    Tensor x_r;
    Tensor x_lenth;
    // input是ArrayList主要是考虑到批量的单元格
    public void create_feed_data(ArrayList<Pair<String, String>> rowCells) {
        int batch_size = rowCells.size();
        long[] input_x_lenth = new long[batch_size];
        byte[][] x_l = new byte[batch_size][];
        byte[][] x_r = new byte[batch_size][];
        for(int i = 0; i<batch_size; i++){
            String left = StringUtils.deleteWhitespace(rowCells.get(i).getLeft());//输入字符里不能出现空格
            String right = StringUtils.deleteWhitespace(rowCells.get(i).getRight());
            input_x_lenth[i] = left.length() + right.length()+1;//左单元格长度,右单元格长度,特殊连接符号<a>
            x_l[i] = getNewString(left).getBytes();
            x_r[i] = getNewString(right).getBytes();
        }
        this.x_l = Tensor.create(x_l,String.class);
        this.x_r = Tensor.create(x_r,String.class);
        this.x_lenth = Tensor.create(input_x_lenth);
    }

    //组装字符串
    String getNewString(String oldString){
        ArrayList<String> newString = new ArrayList<>();
        for (int i=0; i < oldString.length(); i++){
            //将空格符转为<space>
            if (oldString.charAt(i) == ' ') {
                newString.add("<space>");
            }
            else{
                newString.add(String.valueOf(oldString.charAt(i)));
            }
        }
        return StringUtils.join(newString," ");
    }
}

