项目:表格区域定位

Java部分:
	1.util/TrainDataWriter.java 负责抽取特征,生成TFrecord数据
	2.extractors/VectorTableExtractionAlgorithm.java，负责载入模型
	3.detectors/TableRegionCrfAlgorithm.java，复制预测和区域修复，过滤误检等后处理
Python部分:
	python2 main.py  --config line-crf-table


产品上线效果:
	见截图:效果.png

测试结果:
	综合测试对比:
	CRF：
		公告:准确率94.13%,召回率:93.53%

		研报:准确率83.92%,召回率:78.40%

		港股:准确率:67.92%,召回率:60.96%

	矢量+位图:

		公告:准确率97.16，召回率:97.16%

		研报:准确率65.41%，召回率:69.25%

		港股:准确率59.64%,召回率:59.82%
