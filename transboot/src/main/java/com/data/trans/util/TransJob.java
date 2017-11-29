package com.data.trans.util;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.xcontent.XContentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.pool.DruidPooledConnection;
import com.alibaba.fastjson.JSON;
import com.data.trans.model.SystemLog;

/**
 * @author dnc
 * @since 2017年11月19日 下午7:30:25
 * 具体数据迁移执行类
 */
public class TransJob implements Runnable{
	
	private Logger logger = LoggerFactory.getLogger(TransJob.class);  
	
	private DruidDataSource dataSource;
	
	private ElasticDataSource esSource;
	
	private String index;//提交es集群中索引库索引
	
	private String type;//提交es集群中索引库类型
	
	private Integer bulkSize;//每批提交数目
	
	private Integer fetchIdMin;//任务抓取表主键id下限值（包含fetchIdMin）
	
	private Integer fetchIdMax;//任务抓取表主键id上限值（不包含fetchIdMax）
	
	private Integer fetchSize;//每次抓取表记录数
	
	private String fetchTableName;//抓取表名
	
	private String haveTransBetween;//明确指定执行区间,根据此值判断执行模式
	
	public TransJob(){};
	
	public TransJob(DruidDataSource dataSource, ElasticDataSource esSource, String index, String type,
			Integer bulkSize, Integer fetchIdMin, Integer fetchIdMax, Integer fetchSize, String fetchTableName,String haveTransBetween) {
		this.dataSource = dataSource;
		this.esSource = esSource;
		this.index = index;
		this.type = type;
		this.bulkSize = bulkSize;
		this.fetchIdMin = fetchIdMin;
		this.fetchIdMax = fetchIdMax;
		this.fetchSize = fetchSize;
		this.fetchTableName = fetchTableName;
		this.haveTransBetween = haveTransBetween;
	}

	public void trans(Integer beginId,Integer endId,DruidPooledConnection connection,Integer translogId){
		List<SystemLog> logs = new ArrayList<SystemLog>();
    	logs.add(new SystemLog());//占位
		
    	//--------------------mysql读取记录开始--------------------
    	
    	try{
			PreparedStatement prepareStatement = connection.prepareStatement(String.format("select * from %s where id >= ? and id < ?",fetchTableName));
			prepareStatement.setInt(1,beginId);
			prepareStatement.setInt(2,endId);
			ResultSet executeQuery = prepareStatement.executeQuery();
	    	while (executeQuery.next()) {
	    		SystemLog log = new SystemLog();
	    		log.setId(executeQuery.getInt("id"));
	    		log.setOrgId(executeQuery.getInt("org_id"));
	    		log.setUserId(executeQuery.getInt("user_id"));
	    		log.setUnitId(executeQuery.getInt("unit_id"));
	    		log.setModuleCode(executeQuery.getString("module_code"));
	    		log.setApiCode(executeQuery.getString("api_code"));
	    		log.setUserAccount(executeQuery.getString("user_account"));
	    		log.setUnitName(executeQuery.getString("unit_name"));
	    		log.setOpMethod(executeQuery.getString("op_method"));
	    		log.setOpContent(executeQuery.getString("op_content"));
	    		log.setOpResult(executeQuery.getString("op_result"));
	    		log.setOpTime(executeQuery.getTime("op_time"));
	    		log.setModuleParkPlate(executeQuery.getString("module_park_plate"));
	    		logs.add(log);
			}
	    	connection.close();
		}catch (Exception e) {
			
			//记录失败区间[beginId,endId)
			
		}
    	
    	logger.info(Thread.currentThread().getName()+" 获取id区间为：[ "+beginId+" , "+endId+" ) 的数据 [ "+(logs.size()-1)+" ] 条转移到ES!");
    	if(logs.size()<=1){
    		return ;
    	}
    	
    	//--------------------es转移开始--------------------
    	int estransIdMin = logs.get(1).getId();//用于记录批量插入的开始id
    	int estransIdMax = estransIdMin;//用于记录批量插入的结束id
    	try{
    		Client client = esSource.getClient();
        	BulkRequestBuilder bulkRequest = client.prepareBulk();
        	for (int i = 1; i < logs.size(); i++) {
        	    bulkRequest.add(client.prepareIndex(index, type).setSource(JSON.toJSONString(logs.get(i)), XContentType.JSON));
        	    // 每bulkSize条提交一次
        	    if (i % bulkSize == 0 || i == (logs.size()-1)) {
        	    	estransIdMin = estransIdMax;
        	    	estransIdMax = i+1;
        	        BulkResponse actionGet = bulkRequest.execute().actionGet();
        	        System.out.println(actionGet.isFragment());
        	        bulkRequest = client.prepareBulk();//新开一个批次
        	    }
        	}
        	esSource.releaseClient(client);
    	}catch (Exception e) {
			
    		//记录失败区间[estransIdMin,estransIdMax)
		}
    	
		
	}

	@Override
	public void run() {
		try {
			DruidPooledConnection connection = dataSource.getConnection();
			if(StringUtils.hasText(haveTransBetween)){//执行指定区间
				
				//解析haveTransBetween 调用 trans(fetchIdMin+beginId,fetchIdMin+endId,connection);
				
				
			}else{
				//计算一共需要抓取多少趟（可能有余）
				Integer num = (fetchIdMax-fetchIdMin)%fetchSize==0?(fetchIdMax-fetchIdMin)/fetchSize:(fetchIdMax-fetchIdMin)/fetchSize+1;
				
				
				
				for(int i=0;i<num;i++){//自动计算区间
					int beginId = i*fetchSize;
					int endId = beginId+fetchSize;
					if(i!=(num-1)){
						trans(fetchIdMin+beginId,fetchIdMin+endId,connection);
					}else{
						trans(fetchIdMin+beginId,fetchIdMax,connection);
					}
				}
			}
			
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
}
