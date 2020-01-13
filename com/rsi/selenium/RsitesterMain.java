package com.rsi.selenium;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.apache.log4j.Logger;
import org.openqa.selenium.NoSuchElementException;

import com.rsi.dataObject.H2OApplication;
import com.rsi.selenium.factory.H2OTesterConnectionFactory;

public class RsitesterMain {
	final static Logger logger = Logger.getLogger(RsitesterMain.class);
	public static void main(String[] args) {
		Connection conn 		= null;
		Statement stmt 			= null;
		PreparedStatement pstmt	= null;
		ResultSet rsForTestCases= null;
		ResultSet rs 			= null;
		H2OApplication app		= null;
		
		RsiChromeTester chromeTester = new RsiChromeTester();
		
		
		String status 		= "Failed";
		
		// STEP 1: GET Database Connection
		H2OTesterConnectionFactory appFactory = H2OTesterConnectionFactory.getInstance();
		
		conn = appFactory.getDatabaseConnection();
		
		// STEP 2: Read the scheduler table. 
		try {
			stmt = conn.createStatement();
			rs = stmt.executeQuery("SELECT s.id id, s.test_suite_id, s.scheduled_date, t.environment_id environment_id FROM schedulers s, test_suites t WHERE s.test_suite_id = t.id AND s.status = 'READY'");
			
			while (rs.next()) {
				int currentSchedulerId = rs.getInt("id");
				int currentSuiteId = rs.getInt("test_suite_id");
				if (app == null) {
					logger.debug("Trying to fetch the enviroment for id [ " + rs.getInt("environment_id") + " ]");
					app = appFactory.getApplicationEnvironment(rs.getInt("environment_id"));
				}
				if (app == null) {
					logger.debug("Error could not find an environment setting for scheduled job [ " + rs.getString("id") + " ], cannot run test suite id [ " + rs.getInt("environment_id") + " ], moving to the next scheduled job...");
					continue;
				}
				else {
					//chromeTester.loginToApp(app.getUrl(), "CacheUserName", "CachePassword", "btnSearch", app.getLoginName(), app.getLoginPwd(), "success_field");
					logger.debug("app returned is [ " + app.toString() + " ]");
					try {
					//chromeTester.loginToApp(app.getUrl(), "CacheUserName", "CachePassword", "btnSearch", app.getLoginName(), app.getLoginPwd(), "success_field");
					chromeTester.loginToApp(app.getUrl(), app.getLoginField(), app.getPasswordField(), app.getActionButton(), app.getLoginName(), app.getLoginPwd(), "success_field");
					} catch (NoSuchElementException nse) {
						logger.error("Element not found Error [ " + nse.getMessage() + " ]");
						updateSchedulerWithError(conn, currentSchedulerId, currentSuiteId, app);
						continue;
					}
				}
				logger.debug("Now starting to process Scheduled Job Id [ " + rs.getString("id")+ " ], this job was scheduled on [ " + rs.getString(3) + " ], suite id is [ " + rs.getInt("test_suite_id") + " ]");
				// Now try to access all the test cases for the test_suite_id submitted for this run.
				pstmt = conn.prepareStatement("SELECT tc.id as id, tc.field_name as field_name, tc.field_type as field_type, tc.read_element as read_element, tc.input_value as input_value, tc.string as string, tc.action as action, tc.action_url as action_url FROM test_cases tc, case_suites cs WHERE cs.test_case_id = tc.id AND cs.test_suite_id = ? ORDER BY cs.sequence");
				pstmt.setInt(1, rs.getInt("test_suite_id"));
				if (pstmt.execute() == true) {
					rsForTestCases = pstmt.getResultSet();
					while (rsForTestCases.next()) {
						int currentTestCaseId = rsForTestCases.getInt("id");
						logger.debug("Now running test case [ " + rsForTestCases.getString("id") + " ], for field name [ " + rsForTestCases.getString("field_name") +" ] ");
						// Run Chrometest.
						//status = chromeTester.testLoginPage("https://demo.bhix.org", "CacheUserName", "CachePassword", "btnSearch", "guest", "guest", "success_field");
						if (identifyTestCase(rsForTestCases.getString("field_type"), rsForTestCases.getString("input_value"), rsForTestCases.getString("action")) == "INSPECT") {
							try {
							status = chromeTester.testPageElement(app.getUrl(), app.getLoginName(), app.getLoginPwd(), rsForTestCases.getString("field_name"), rsForTestCases.getString("field_type"), rsForTestCases.getString("read_element"));
							}catch (NoSuchElementException nse) {
								logger.error(nse.getMessage());
								updateTestCaseWithError(conn, currentTestCaseId, currentSchedulerId);
								//nse.printStackTrace();
								continue;
							}
							
						}
						//else if () {
							
						//}
						logger.debug("Status returned is [ " + status + " ]");	
					}
				}
				//updateTestSuiteResultWithComplete(conn, );
				updateSchedulerWithComplete(conn, currentSchedulerId, currentSuiteId);
				
			}
		} catch (SQLException e) {
			logger.error("Looks like Something bad happened while running the query, most likely referential data does not exist. ");
			logger.error (e.getMessage());
			//e.printStackTrace();
		} finally {
			if (rs != null) {
				try {
		            rs.close();
		        } catch (SQLException sqlEx) { } // ignore

		        rs = null;
			}
			if (stmt != null) {
		        try {
		            stmt.close();
		        } catch (SQLException sqlEx) { } // ignore

		        stmt = null;
		    }
			
			chromeTester.getDriver().quit();
		}
	    
	}

	private static void updateSchedulerWithComplete(Connection conn,
			int currentSchedulerId, int currentSuiteId) {
		PreparedStatement pstmt = null;
		try {
			pstmt = conn.prepareStatement("UPDATE schedulers SET status = 'Complete' WHERE id = ?");
			pstmt.setInt(1, currentSchedulerId);
			if (pstmt.execute()) {
				logger.info("Updated Scheduler id [" + currentSchedulerId + " ], with Complete");
			}
			else {
				logger.error("Could not update the Scheduler id [" + currentSchedulerId + " ] with Complete Status. Please delete it manually. ");
			}
				
		} catch (SQLException e) {
			// TODO Auto-generated catch block
			logger.error("Could not update the Scheduler id [" + currentSchedulerId + " ] with Complete Status. Please delete it manually. ");
			e.printStackTrace();
		}finally {
			try {
				pstmt.close();
			} catch (SQLException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		try {
			pstmt = conn.prepareStatement("INSERT INTO result_suites (rd_id, test_suite_id) VALUES(?,?)");
			pstmt.setInt(1, currentSchedulerId);
			pstmt.setInt(2, currentSuiteId);
			if (pstmt.execute()) {
				logger.info("Updated Scheduler id [" + currentSchedulerId + " ], with Error");
			}
			else {
				logger.error("Could not update the Scheduler id [" + currentSchedulerId + " ] with Error Status. Please delete it manually. ");
			}
				
		} catch (SQLException e) {
			// TODO Auto-generated catch block
			logger.error("Could not update the Scheduler id [" + currentSchedulerId + " ] with Error Status. Please delete it manually. ");
			e.printStackTrace();
		} finally {
			try {
				pstmt.close();
			} catch (SQLException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		
	}

	private static void updateTestCaseWithError(Connection conn,
			int currentTestCaseId, int currentSchedulerId) {
		PreparedStatement pstmt = null;
		try {
			pstmt = conn.prepareStatement("UPDATE result_cases SET rd_id = 2 WHERE test_case_id = ?");
			pstmt.setInt(1, currentTestCaseId);
			if (pstmt.execute()) {
				logger.info("Updated TestCase Result id [" + currentTestCaseId + " ], with Error");
			}
			else {
				logger.error("Could not update the Test Case id in Results [" + currentTestCaseId + " ] with Error Status. Please delete it manually. ");
			}
				
		} catch (SQLException e) {
			// TODO Auto-generated catch block
			logger.error("Could not update the Test Case with Result for id [" + currentTestCaseId + " ] with Error Status. Please delete it manually. ");
			e.printStackTrace();
		} finally {
			try {
				pstmt.close();
			} catch (SQLException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		
	}

	private static void updateSchedulerWithError(Connection conn, int currentSchedulerId, int currentSuiteId, H2OApplication app) {
		// TODO Update current Scheduler as Error. This will tell the analyzer that somethin bad happened.
		PreparedStatement pstmt = null;
		try {
			pstmt = conn.prepareStatement("UPDATE schedulers SET status = 'Error' WHERE id = ?");
			pstmt.setInt(1, currentSchedulerId);
			if (pstmt.execute()) {
				logger.info("Updated Scheduler id [" + currentSchedulerId + " ], with Error");
			}
			else {
				logger.error("Could not update the Scheduler id [" + currentSchedulerId + " ] with Error Status. Please delete it manually. ");
			}
				
		} catch (SQLException e) {
			// TODO Auto-generated catch block
			logger.error("Could not update the Scheduler id [" + currentSchedulerId + " ] with Error Status. Please delete it manually. ");
			e.printStackTrace();
		} finally {
			try {
				pstmt.close();
			} catch (SQLException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		try {
			pstmt = conn.prepareStatement("INSERT INTO result_suites (rd_id, test_suite_id) VALUES(?,?)");
			pstmt.setInt(1, currentSchedulerId);
			pstmt.setInt(2, currentSuiteId);
			if (pstmt.execute()) {
				logger.info("Updated Scheduler id [" + currentSchedulerId + " ], with Error");
			}
			else {
				logger.error("Could not update the Scheduler id [" + currentSchedulerId + " ] with Error Status. Please delete it manually. ");
			}
				
		} catch (SQLException e) {
			// TODO Auto-generated catch block
			logger.error("Could not update the Scheduler id [" + currentSchedulerId + " ] with Error Status. Please delete it manually. ");
			e.printStackTrace();
		} finally {
			try {
				pstmt.close();
			} catch (SQLException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		
		
	}

	private static String identifyTestCase(String string, String string2,
			String string3) {
		// TODO Auto-generated method stub
		return "INSPECT";
	}

}
