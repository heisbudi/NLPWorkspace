/**
 * 
 */
package metdemo.DataBase;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Vector;
import java.util.zip.GZIPOutputStream;

/**
 * @author Shlomo
 * April 2007
 * 
 * idea: cache database writes until either close, explciit flush or optimize command called
 * will write to large file
 * wlil clean up before starting
 */
public class EMTMysqlCachedDatabaseConnection implements EMTDatabaseConnection {

	final String typedb = DatabaseManager.sMYSQL;
	final String default_db_url = "jdbc:mysql:";
	private String cacheFileName;
	//private BufferedWriter cacheFileHandle;
	private ObjectOutputStream cacheFileHandle;

	
	private boolean haveSomethingInCache = false;
	/** user name. */
	private String user = "user";
	/** password. */
	private String passwd = "password";

	private String hostname = "127.0.0.1";
	
	private String dbName = "emt01";
	private String driverName = "com.mysql.jdbc.Driver";

	Connection connection;
  //TODO remove globally move locally
	Statement statement;


	
	
	
	public EMTMysqlCachedDatabaseConnection(String host, String dbn, String usern, String passwdn) {
		
		System.out.println("starting the cache test");
		hostname = host;
		dbName = dbn;
		user = usern;
		passwd = passwdn;
		
		cacheFileName = new String(typedb + hostname + dbName+".dbcache");
		
		
		//lets try to open the cache file....delete if not empty
		//making STRONG assumption that when done will be deleting...if crash we arent doing anything
		
		try{
		//cacheFileHandle =  new BufferedWriter(new FileWriter(cacheFileName));
		
		FileOutputStream out = new FileOutputStream(cacheFileName);
		GZIPOutputStream gout = new GZIPOutputStream(out);

		cacheFileHandle = new ObjectOutputStream(gout);

		
		
		
		
		}catch(IOException ioe){
			ioe.printStackTrace();
		}
		
		
		
	}

	/* (non-Javadoc)
	 * @see metdemo.DataBase.EMTDatabaseConnection#getCountByDate(java.lang.String)
	 */
	public int getCountByDate(String date) {
		Flush(false);
		int max_rows = -1;
		
		try {
			PreparedStatement ps_getcount_date = connection.prepareStatement("select count(*) from email where dates >= ?");
			ps_getcount_date.setString(1, date);
			ResultSet resultSet = ps_getcount_date.executeQuery();
			if (resultSet.next()) {
				max_rows = resultSet.getInt(1);
			}
			resultSet.close();
		} catch (SQLException ex) {
			//ex.printStackTrace();
		}
		return max_rows;
	}

	/* (non-Javadoc)
	 * @see metdemo.DataBase.EMTDatabaseConnection#getCountByTable(java.lang.String)
	 */
	public int getCountByTable(String table) {
		Flush(false);
		
		int max_rows =-1;
		try {
			PreparedStatement ps_getcount = connection.prepareStatement("SELECT count(*) FROM ?");
			ps_getcount.setString(1, table);
			ResultSet resultSet = ps_getcount.executeQuery();
			if (resultSet.next()) {
				max_rows = resultSet.getInt(1);
			}
			resultSet.close();
		} catch (SQLException ex) {
	
		}

		return max_rows;
	}

	/* (non-Javadoc)
	 * @see metdemo.DataBase.EMTDatabaseConnection#getBodyByMailref(java.lang.String)
	 */
	public String[] getBodyByMailref(String EmailRef) {
		
		Flush(false);
		
		Vector result = new Vector();
		
		try{
		PreparedStatement ps_body_by_mailref = connection.prepareStatement("select body from message where mailref = ?");
		ps_body_by_mailref.setString(1, EmailRef);
		ResultSet resultSet = ps_body_by_mailref.executeQuery();
			//metaData = resultSet.getMetaData();
			//numberOfColumns = metaData.getColumnCount();
			//columnNames = new String[numberOfColumns];
		// Get the column names and cache them.
		//for (int column = 0; column < numberOfColumns; column++) {
		//	columnNames[column] = metaData.getColumnLabel(column + 1);
		//}

		// Get all rows.
		InputStream is;
		int c;
		byte buf[] = new byte[512];//was 256
		while (resultSet.next()) {
			//String[] temp = new String[numberOfColumns];
			String temp = new String();
			//for (int i = 1; i <= numberOfColumns; i++)

			try {
				java.sql.Blob blob = resultSet.getBlob(1);
				is = blob.getBinaryStream();
				StringBuffer small2 = new StringBuffer(256);
				while ((c = is.read(buf)) != -1)
					small2.append(new String(buf, 0, c));

				temp = small2.toString();
			} catch (IOException e) {
				temp = "null";
			}

			result.addElement(temp);
		}
		resultSet.close();
		}catch(SQLException see){
			System.out.println(see);
			
		}
		return (String[]) result.toArray(new String[result.size()]);
	}

	/* (non-Javadoc)
	 * @see metdemo.DataBase.DatabaseConnection#connect()
	 */
	public boolean connect(boolean shouldCreate) throws DatabaseConnectionException {
		try {
			Class.forName(driverName);
			String url = this.default_db_url +"//" + hostname +"/" + dbName;				
			connection = DriverManager.getConnection(url, user, passwd);

			statement = connection.createStatement();

			statement.setEscapeProcessing(false);

			//TODO: add stuff here
			//do prepared statements
			return true;
		} catch (ClassNotFoundException ex) {
			System.err.println("Cannot find the database driver classes.");
			System.err.println(ex);
			return false;
		} catch (SQLException ex) {
			System.err.println("Cannot connect to this database.");
			System.err.println(ex);
			return false;
		}
	}

	/* (non-Javadoc)
	 * @see metdemo.DataBase.DatabaseConnection#shutdown()
	 */
	public boolean shutdown() throws DatabaseConnectionException {
		
		Flush(true);
		try {
			
			
			
			
			//if (resultSet != null) {
		//		resultSet.close();
		//	}
			if (statement != null) {
				statement.close();
			}
			if (connection != null) {
				connection.close();
			}
			return true;
		} catch (SQLException ex) {
			ex.printStackTrace();
			return false;
		}
	}

	/* (non-Javadoc)
	 * @see metdemo.DataBase.DatabaseConnection#getSQLData(java.lang.String)
	 */
	public String[][] getSQLData(String query) throws SQLException {
		
		Flush(false);
		//		System.out.println("q2:" + query);
		ArrayList result = new ArrayList();
		
		try {
			ResultSet resultSet = statement.executeQuery(query);
			ResultSetMetaData metaData = resultSet.getMetaData();
			int numberOfColumns = metaData.getColumnCount();
			// Get all rows.
			while (resultSet.next()) {
				String[] temp = new String[numberOfColumns];
				for (int i = 1; i <= numberOfColumns; i++) {
					Object obj = resultSet.getObject(i);
					if (obj != null) {
	//					System.out.print(" "+obj.getClass().getName());
						if (obj instanceof java.sql.Blob
								||	obj.getClass().getName().startsWith("[B"))//   ||obj
						// instanceof
						// java.sql.Blob)
						{
//							System.out.println("got blob");
							try {
								java.sql.Blob blob = resultSet.getBlob(i);
								InputStream is = blob.getBinaryStream();
								int c;
								StringBuffer small2 = new StringBuffer(256);
								byte buf[] = new byte[256];
								while ((c = is.read(buf)) != -1)
									small2.append(new String(buf, 0, c));

								temp[i - 1] = small2.toString();
							} catch (IOException e) {
								temp[i - 1] = "null";
							}

						} else
							temp[i - 1] = (resultSet.getObject(i)).toString();
					} else {
						temp[i - 1] = "null";
					}
				}
				result.add(temp);
			}
			resultSet.close();
			//return (String[][])result.toArray(new String[result.size()]);
			String [][]data = new String[result.size()][numberOfColumns];
			int i=0;
			for(Iterator it = result.iterator();it.hasNext();){
				data[i++] = (String[])it.next(); 
			}
			
			
			return data;
			
		} catch (SQLException ex) {
			throw ex;
		}
	}

	/* (non-Javadoc)
	 * @see metdemo.DataBase.DatabaseConnection#getCountOnSQLData(java.lang.String)
	 */
	public int getCountOnSQLData(String query) throws SQLException {
		
		Flush(false);
		int counter=0;
		try {
			ResultSet resultSet = statement.executeQuery(query);
			// Get all rows.
			while (resultSet.next()) {
				counter++;
			}
			resultSet.close();
			} catch (SQLException ex) {
			throw ex;
		}
		return counter;	
	}

	/* (non-Javadoc)
	 * @see metdemo.DataBase.DatabaseConnection#updateSQLData(java.lang.String)
	 */
	public boolean updateSQLData(String query) throws SQLException {
		
		haveSomethingInCache = true;
		try{
		cacheFileHandle.writeObject(query);
			//cacheFileHandle.write(query);
		//cacheFileHandle.write("\n");
		}
		catch(IOException e){
			e.printStackTrace();
			
		}
		return true;
	}

	/* (non-Javadoc)
	 * @see metdemo.DataBase.DatabaseConnection#getDatabaseName()
	 */
	public String getDatabaseName() {
		return dbName;
	}

	/* (non-Javadoc)
	 * @see metdemo.DataBase.DatabaseConnection#getTypeDB()
	 */
	public String getTypeDB() {
		return DatabaseManager.sMYSQL;
	}

	/* (non-Javadoc)
	 * @see metdemo.DataBase.DatabaseConnection#getBinarySQLData(java.lang.String)
	 */
	public byte[][] getBinarySQLData(String query) throws SQLException {
		
		Flush(false);
		
		Vector result = new Vector();

		try {
			ResultSet resultSet = statement.executeQuery(query);
			//metaData = resultSet.getMetaData();
			//numberOfColumns = metaData.getColumnCount();
			//columnNames = new String[numberOfColumns];
			// Get the column names and cache them.
			//for (int column = 0; column < numberOfColumns; column++) {
			//	columnNames[column] = metaData.getColumnLabel(column + 1);
			//}
			
			// Get all rows.
			while (resultSet.next()) {
				result.add(resultSet.getBytes(1));
			}

			/*
			 * byte[] temp = new String[numberOfColumns]; for (int i = 1; i <=
			 * numberOfColumns; i++) { Object obj = resultSet.getObject(i);
			 * if(obj != null) { //System.out.print("
			 * "+obj.getClass().getName());
			 * if(obj.getClass().getName().startsWith("[B"))// ||obj instanceof
			 * java.sql.Blob) { // System.out.println("got blob"); try{
			 * java.sql.Blob blob = resultSet.getBlob(i); InputStream is =
			 * blob.getBinaryStream(); int c; StringBuffer small2 = new
			 * StringBuffer(256); byte buf[] = new byte[256]; while ((c =
			 * is.read(buf)) != -1) small2.append(new String(buf,0,c));
			 * 
			 * 
			 * temp[i-1] = small2.toString(); }catch(IOException
			 * e){temp[i-1]="null";} } else temp[i-1] =
			 * (resultSet.getObject(i)).toString(); } else { temp[i-1] = "null"; } }
			 * result.addElement(temp); } resultSet.close();
			 * 
			 * flag = true;
			 */
			resultSet.close();
		} catch (SQLException ex) {
			throw ex;
			//String message = ex.toString();
			//System.err.println(message);
			//ex.printStackTrace();
		}
		byte temp[][] = new byte[result.size()][];
		
		for(int i=0;i<result.size();i++)
		{
			temp[i] = (byte[])result.elementAt(i);
		}
		
		return temp;
		/*
		 * return flag;
		 */
	}

	/* (non-Javadoc)
	 * @see metdemo.DataBase.DatabaseConnection#getHash()
	 */
	public String getHash() {
		// TODO Auto-generated method stub
		return null;
	}

	/* (non-Javadoc)
	 * @see metdemo.DataBase.DatabaseConnection#getConnection()
	 */
	public Connection getConnection() {
		// TODO Auto-generated method stub
		return null;
	}

	/* (non-Javadoc)
	 * @see metdemo.DataBase.DatabaseConnection#getSQLDataByColumn(java.lang.String, int)
	 */
	public String[] getSQLDataByColumn(String query, int Column) throws SQLException 
	{
		Flush(false);
		
		ArrayList result = new ArrayList();
		if(Column<=0)
		{
			throw new SQLException("COlumn offset is from 1");
		}
		try {
			ResultSet resultSet = statement.executeQuery(query);
			ResultSetMetaData metaData = resultSet.getMetaData();
			int numberOfColumns = metaData.getColumnCount();
			if(Column > numberOfColumns)
			{
				throw new SQLException("Column number too high");
			}
			
			// Get all rows.
			while (resultSet.next()) {
				String temp = new String();
					Object obj = resultSet.getObject(Column);
					if (obj != null) {
						//System.out.print(" "+obj.getClass().getName());
						if (obj instanceof Blob ||	obj.getClass().getName().startsWith("[B"))
						{
							try {
								java.sql.Blob blob = resultSet.getBlob(Column);
								InputStream is = blob.getBinaryStream();
								int c;
								StringBuffer small2 = new StringBuffer(256);
								byte buf[] = new byte[256];
								while ((c = is.read(buf)) != -1)
									small2.append(new String(buf, 0, c));

								temp = small2.toString();
							} catch (IOException e) {
								temp = "null";
							}
						} else
							{
							temp = (resultSet.getObject(Column)).toString();
							}
					} else {
						temp = "null";
					}
				
				result.add(temp);
		}
			resultSet.close();
			return (String[])result.toArray(new String[result.size()]);
		} catch (SQLException ex) {
			throw ex;
		}
	}

	/* (non-Javadoc)
	 * @see metdemo.DataBase.EMTDatabaseConnection#createNewEMTDatabase(java.lang.String)
	 */
	public boolean createNewEMTDatabase(String name) {
		return DatabaseManager.createNewEMTDatabase(name,this.statement,DatabaseManager.MYSQL);
	}

	/* (non-Javadoc)
	 * @see metdemo.DataBase.DatabaseConnection#switchCreate(java.lang.String)
	 */
	public boolean switchCreate(String targetDB) {
		try{
		updateSQLData("use " + targetDB);	
		this.dbName = targetDB;
		return true;
		}catch(SQLException s){
		//	System.out.println(s);
			try {
				updateSQLData("create database " + targetDB);
				updateSQLData("use " + targetDB);	
				this.dbName = targetDB;
				return true;
			} catch (SQLException sl) {
				System.out.println("When trying to create db: " + sl);
			}
		}
		return false;
	}
	/* (non-Javadoc)
	 * @see metdemo.DataBase.DatabaseConnection#prepareStatementHelper(java.lang.String)
	 */
	public PreparedStatement prepareStatementHelper(String p) throws SQLException{
		return connection.prepareStatement(p);
	}

	/* (non-Javadoc)
	 * @see metdemo.DataBase.DatabaseConnection#getSQLDataLimited(java.lang.String, int)
	 */
	public String[][] getSQLDataLimited(String Query, int Limit) throws SQLException {
		return getSQLData(Query + " limit " + Limit);
	}

	/* (non-Javadoc)
	 * @see metdemo.DataBase.EMTDatabaseConnection#setAutocommit(boolean)
	 */
	public void setAutocommit(boolean flag) {
		// TODO Auto-generated method stub
		try{
			boolean current = connection.getAutoCommit();
			
			this.connection.setAutoCommit(flag);
			if(!flag){
				this.connection.commit();
			}
			
		}catch(SQLException se){ System.out.println("in autocommit:");se.printStackTrace();}
	}
	
	/* (non-Javadoc)
	 * @see metdemo.DataBase.EMTDatabaseConnection#getSubjectByMailref(java.lang.String)
	 */
	public String getSubjectByMailref(String EmailRef) {
		String result = null;

		try {
			PreparedStatement ps_body_by_mailref = connection
					.prepareStatement("select subject from email where mailref = ?");
			ps_body_by_mailref.setString(1, EmailRef);
			ResultSet resultSet = ps_body_by_mailref.executeQuery();
			
			if (resultSet.next()) {
				//String[] temp = new String[numberOfColumns];
				try {
					result = resultSet.getString(1);
				} catch (SQLException e) {
					result = "";
				}
			}
			resultSet.close();
		} catch (SQLException see) {
			System.out.println(see);
			
		}
		return result;
	}

	/* (non-Javadoc)
	 * @see metdemo.DataBase.EMTDatabaseConnection#getBodyAndTypeByMailref(java.lang.String)
	 */
	public String[][] getBodyAndTypeByMailref(String EmailRef) {
		
		Flush(false);
		
		Vector result = new Vector();
		Vector types = new Vector();
		try{
		PreparedStatement ps_body_by_mailref = connection.prepareStatement("select body,type from message where mailref = ?");
		ps_body_by_mailref.setString(1, EmailRef);
		ResultSet resultSet = ps_body_by_mailref.executeQuery();
		
		// Get all rows.
		InputStream is;
		int c;
		byte buf[] = new byte[512];//was 256
		while (resultSet.next()) {
			//String[] temp = new String[numberOfColumns];
			String temp = new String();
			
			//for (int i = 1; i <= numberOfColumns; i++)

			try {
				java.sql.Blob blob = resultSet.getBlob(1);
				types.addElement(resultSet.getString(2));
				is = blob.getBinaryStream();
				StringBuffer small2 = new StringBuffer(256);
				while ((c = is.read(buf)) != -1)
				{
					small2.append(new String(buf, 0, c));
				}
				temp = small2.toString();
			} catch (IOException e) {
				temp = "null";
			}

			result.addElement(temp);
		}
		resultSet.close();
		}catch(SQLException see){
			System.out.println(see);
			
		}
		
		String [][]resultdata = new String[result.size()][2];
		for(int i=0;i<result.size();i++){
			resultdata[i][0] = (String)result.elementAt(i);
			resultdata[i][1] = (String)types.elementAt(i);	
			
			
		}
		
		
		//return (String[]) result.toArray(new String[result.size()]);
		return resultdata;
	}

	private void Flush(boolean shutdown){
		
		//TODO: fill in code here
		if(!haveSomethingInCache){
			return;
		}
		else{
			try{
			this.cacheFileHandle.flush();
			if(shutdown)			
			{
				cacheFileHandle.close();
			}
			
			
			//so can empty the cache
			haveSomethingInCache = false;
			}catch(IOException ee){
				ee.printStackTrace();
			}
			
		}
		
		
		
		
	}
	/*updatequerry:
	 * ResultSet resultSet = null;
		try {
			
			if (statement.execute(query)) {
				resultSet = statement.getResultSet();
			} else
				return false;
			//      resultSet = statement.executeQuery(query);
			if (resultSet == null) {
				throw new SQLException("Could not execute query");
			}
			resultSet.close();
		} catch (SQLException e) {
			if (resultSet != null) {
				resultSet.close();
			}
			throw e;
		}
		
		
	 */
	

	public void doPrepared(final PreparedStatement p) throws SQLException {
		
		p.executeUpdate();
	
	
	
		
		/*try{
		  cacheFileHandle.writeObject(p.toString());
		}catch(IOException e){
			e.printStackTrace();
		}
		/*String sql = p.toString();
		
		int a = sql.indexOf(" - ");
		if(a >0)
			updateSQLData(sql.substring(a+3));
		else
			updateSQLData(sql);
			*/
	}

	
	
	
	
	public String[][] getAllDatabases() throws DatabaseConnectionException, SQLException {
		return getSQLData("show databases");
	}
	
	
}
