1.模型入口文件:cellMerge.java,使用前只需要指定模型地址

2.数据输入格式说明:
	深圳市万纬物&流管理有限公司,其中&是切分标记,建议&左右两边各至少要有２个字符
3.结果说明:
	score:0.9966271,predict:0,其中score代表本次结果的可信度，predict中１代表要和，0代表不合
	建议置信度为0.8或者0.75

4.支持批量，单次耗时在1ms左右

5.模型准率说明:
	数据量:1350万，正负样本比例:1.2:1,测试数据比例:5%
	准确率:94.7%

6.版本修订记录:
	1.修改batch必须为固定大小的bug
	2.调低vector_size，使其更对数据长度的依赖更小
	3.添加RNN指定有效序列的长度
	4.修改代码框架为胡迪的，整体速度只需要原来的1/2,模型内部自带向量化逻辑
	5.支持接口可动态长度，不作截断处理，模型内部由原来的取RNN输出变成取最后的State，由此来支持可变长
	6.增加Normal Batch机制，有效的解决了过拟合的问题，但是置信度会下降，建议将置信度阈值进行调整
	7.修改对空格的处理办法，由原来的删除空格修改为用<space>代替

7.结果举例:
senetence:名次<a>第一名,score:0.87877744,predict:0
senetence:第二名<a>第一名,score:0.8992444,predict:0
senetence:第二<a>名,score:0.6613217,predict:1
senetence:产品名称<a>1500型聚丙烯外排液厢式配板,score:0.8933121,predict:0
senetence:齿盘式滤饼破碎机<a>搅拌机,score:0.89718777,predict:0
senetence:自动加<a>药机,score:0.87584144,predict:1
senetence:信息披露义务人是否拟<a>于未来12个月内继续增持,score:0.88077116,predict:1
senetence:累计毛利率<a>88.99%,score:0.8901993,predict:0
senetence:基金名称<a>闻名福沃汽车产业私募股权投资基金,score:0.8961055,predict:0
senetence:基金<a>名称,score:0.91202646,predict:1
senetence:基 金<a>名 称,score:0.84416264,predict:1
senetence:成立时间<a>2017年5月17日,score:0.89253014,predict:0
senetence:基本情况<a>上市公司名称,score:0.8921941,predict:0
senetence:引进LivaNova PL技术；监测睡眠呼吸暂停和低通气事件，预警伴发心血 <a>管疾病；SafeR生理性起搏，降低房颤发生和心衰住院风险；体积最小,score:0.8763464,predict:1


	
