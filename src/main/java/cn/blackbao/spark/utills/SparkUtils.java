package cn.blackbao.spark.utills;

import org.apache.spark.SparkConf;
import org.apache.spark.SparkContext;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.sql.AnalysisException;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SQLContext;
import org.apache.spark.sql.SparkSession;

import com.alibaba.fastjson.JSONObject;

import cn.blackbao.spark.configration.ConfigurationManager;
import cn.blackbao.spark.constant.Constants;

/**
 * Spark工具类
 * @author Administrator
 *
 */
public class SparkUtils {
	
	/**
	 * 根据当前是否本地测试的配置
	 * 决定，如何设置SparkConf的master
	 */
	public static void setMaster(SparkConf conf) {
		boolean local = ConfigurationManager.getBoolean(Constants.SPARK_LOCAL);
		if(local) {
			conf.setMaster("local");  
		}  
	}
	
	/**
	 * 获取SQLContext
	 * 如果spark.local设置为true，那么就创建SQLContext；否则，创建HiveContext
	 * @param sc
	 * @return
	 */
	public static SparkSession getSQLContext(SparkContext sc) {
			return SparkSession.builder().config(sc.conf()).getOrCreate();
	}
	
	/**
	 * 生成模拟数据
	 * 如果spark.local配置设置为true，则生成模拟数据；否则不生成
	 * @param sc
	 * @param sqlContext
	 */
	public static void mockData(JavaSparkContext sc, SparkSession sparkSession) {
		boolean local = ConfigurationManager.getBoolean(Constants.SPARK_LOCAL);
		if(local) {
			try {
				MockData.mock(sc, sparkSession);
			} catch (AnalysisException e) {
				e.printStackTrace();
			}  
		}
	}
	
	/**
	 * 获取指定日期范围内的用户行为数据RDD
	 * @param sqlContext
	 * @param taskParam
	 * @return
	 */
	public static JavaRDD<Row> getActionRDDByDateRange(
			SQLContext sqlContext, JSONObject taskParam) {
		String startDate = ParamUtils.getParam(taskParam, Constants.PARAM_START_DATE);
		String endDate = ParamUtils.getParam(taskParam, Constants.PARAM_END_DATE);
		
		String sql = 
				"select * "
				+ "from user_visit_action "
				+ "where date>='" + startDate + "' "
				+ "and date<='" + endDate + "'";  
//				+ "and session_id not in('','','')"
		
		Dataset<Row> dataset = sqlContext.sql(sql);
		
		/**
		 * 这里就很有可能发生上面说的问题
		 * 比如说，Spark SQl默认就给第一个stage设置了20个task，但是根据你的数据量以及算法的复杂度
		 * 实际上，你需要1000个task去并行执行
		 * 
		 * 所以说，在这里，就可以对Spark SQL刚刚查询出来的RDD执行repartition重分区操作
		 */
		
//		return actionDF.javaRDD().repartition(1000);
		
		return dataset.rdd().toJavaRDD();
	}
	
}
