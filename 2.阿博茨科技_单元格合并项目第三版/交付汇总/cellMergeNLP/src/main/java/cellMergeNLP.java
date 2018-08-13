import org.tensorflow.*;
import org.tensorflow.Session.Runner;
import org.apache.commons.lang3.StringUtils;
import java.io.*;
import java.util.*;


class cellMergeNLP {
    private static Session tfSession = null;
    //模型路径
    private static String module_path = "/home/jhqiu/模型归档/单元格合并第三版/cellMergeNLP/src/main/resources/model";
    private static ArrayList<Float> resultScores=new ArrayList<Float>();
    private static ArrayList<Long> resultPredicts=new ArrayList<Long>();
	public static void main(String[] args) {
        //载入tensorflow模型和字典
		load_module();
		for(int j=0;j<10;j++) {
            //显示所有结果
            ArrayList<String[]> BatchSentences=new ArrayList<String[]>();
            BatchSentences.add(new String[] {"名次","第一名"});
            BatchSentences.add(new String[] {"第二名","第一名"});
            BatchSentences.add(new String[] {"第二","名"});
            BatchSentences.add(new String[] {"产品名称","1500型聚丙烯外排液厢式配板"});
            BatchSentences.add(new String[] {"齿盘式滤饼破碎机","搅拌机"});
            BatchSentences.add(new String[] {"自动加","药机"});
            BatchSentences.add(new String[] {"信息披露义务人是否拟","于未来12个月内继续增持"});
            BatchSentences.add(new String[] {"累计毛利率","88.99%"});
            BatchSentences.add(new String[] {"基金名称","闻名福沃汽车产业私募股权投资基金"});
            BatchSentences.add(new String[] {"基金","名称"});
            BatchSentences.add(new String[] {"基 金","名 称"});
            BatchSentences.add(new String[] {"成立时间","2017年5月17日"});
            BatchSentences.add(new String[] {"基本情况","上市公司名称"});
            BatchSentences.add(new String[] {"引进LivaNova PL技术；监测睡眠呼吸暂停和低通气事件，预警伴发心血 ","管疾病；SafeR生理性起搏，降低房颤发生和心衰住院风险；体积最小"});

            long begin = System.currentTimeMillis();
            cellMergePredict(BatchSentences);
            for (int i = 0; i < BatchSentences.size(); i++) {
                System.out.println("senetence:" + StringUtils.join(BatchSentences.get(i),"<a>") + ",score:" + resultScores.get(i) + ",predict:" + resultPredicts.get(i));
            }
            System.out.println("单次耗时:" + (System.currentTimeMillis() - begin)/BatchSentences.size());
        }
        return;
	}
	
	//载入tensorflow模型和字典
	public static void load_module(){
        if (tfSession == null) {
            SavedModelBundle savedModelBundle = SavedModelBundle.load(module_path, "serve");
            tfSession = savedModelBundle.session();
            /*
            Iterator<Operation> operations = savedModelBundle.graph().operations();
            for (Iterator<Operation> it = operations; it.hasNext(); ) {
                Operation operation = it.next();
                System.out.println(operation.name());
            }
            */
        }
	}

    //入参是你想输入的多句话
    public static void cellMergePredict(ArrayList<String[]> sentences){
        resultScores.clear();//上次结果清0
        resultPredicts.clear();
        int batch_size=sentences.size();
        float[] scores=new float[batch_size];
        long[] predicts=new long[batch_size];
        feed_data f=new feed_data();
        f.create_feed_data(sentences);
        Runner run=tfSession.runner().feed("x_l", f.x_l).feed("x_r", f.x_r).feed("l", f.x_lenth);
        List<Tensor<?>> output=run.fetch("scores").fetch("class").run();
        output.get(0).copyTo(scores);
        output.get(1).copyTo(predicts);
        for (int i=0;i<batch_size;i++){
            resultScores.add(scores[i]);
            resultPredicts.add(predicts[i]);
        }
    }
}

class feed_data {
    Tensor x_l;
    Tensor x_r;
    Tensor x_lenth;
    public void create_feed_data(ArrayList<String[]> senetences) {
        int batch_size=senetences.size();
        long[] input_x_lenth=new long[batch_size];
        byte[][] x_l=new byte[batch_size][];
        byte[][] x_r=new byte[batch_size][];
        for(int i=0;i<batch_size;i++){
            String left=senetences.get(i)[0];//输入字符里不能出现空格
            String right=senetences.get(i)[1];
            input_x_lenth[i]=left.length()+right.length()+1;//左单元格长度,右单元格长度,特殊连接符号<a>
            x_l[i]=getNewString(left).getBytes();
            x_r[i]=getNewString(right).getBytes();
        }
        this.x_l=Tensor.create(x_l,String.class);
        this.x_r=Tensor.create(x_r,String.class);
        this.x_lenth=Tensor.create(input_x_lenth);
    }

    //组装字符串
    String getNewString(String oldString){
        ArrayList<String> newString =new ArrayList<String>();
        for (int i=0;i<oldString.length();i++){
            //将空格符转为<space>
            if (oldString.charAt(i)==' ') {
                newString.add("<space>");
            }
            else{
                newString.add(String.valueOf(oldString.charAt(i)));
            }
        }
        return StringUtils.join(newString," ");
    }
}

