/*
       Copyright 2017 IBM Corp All Rights Reserved

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */

package com.ibm.hybrid.cloud.sample.portfolio;

import java.io.PrintWriter;
import java.io.StringWriter;

//Logging (JSR 47)
import java.util.logging.Level;
import java.util.logging.Logger;

//JMS 2.0
import javax.jms.JMSException;
import javax.jms.Queue;
import javax.jms.QueueConnection;
import javax.jms.QueueConnectionFactory;
import javax.jms.QueueSender;
import javax.jms.QueueSession;
import javax.jms.Session;
import javax.jms.TextMessage;

//JSON-P (JSR 353).  The replaces my old usage of IBM's JSON4J (com.ibm.json.java.JSONObject)
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;

//JNDI 1.0
import javax.naming.InitialContext;
import javax.naming.NamingException;

//Servlet 3.1
import javax.servlet.http.HttpServletRequest;

//JAX-RS 2.0 (JSR 339)
import javax.ws.rs.core.Application;
import javax.ws.rs.core.Context;
import javax.ws.rs.ApplicationPath;
import javax.ws.rs.GET;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.Path;


@ApplicationPath("/")
@Path("/")
/** Determine loyalty status based on total portfolio value.
 *  Also send a notification when status changes for a given user.
 */
public class LoyaltyLevel extends Application {
	private static Logger logger = Logger.getLogger(LoyaltyLevel.class.getName());

	private static final String NOTIFICATION_Q   = "jms/Portfolio/NotificationQueue";
	private static final String NOTIFICATION_QCF = "jms/Portfolio/NotificationQueueConnectionFactory";

	private Queue queue = null;
	private QueueConnectionFactory queueCF = null;
	private boolean initialized = false;

    @GET
    @Path("/")
	@Produces("application/json")
//	@RolesAllowed({"StockTrader", "StockViewer"}) //Couldn't get this to work; had to do it through the web.xml instead :(
	public JsonObject getLoyalty(@QueryParam("owner") String owner, @QueryParam("loyalty") String oldLoyalty, @QueryParam("total") double total, @Context HttpServletRequest request) {
		JsonObjectBuilder loyaltyLevel = Json.createObjectBuilder();

		String loyalty = "Basic";
		if (total > 1000000.00) {
			loyalty = "Platinum";
		} else if (total > 100000.00) {
			loyalty = "Gold";
		} else if (total > 50000.00) {
			loyalty = "Silver";
		} else if (total > 10000.00) {
			loyalty = "Bronze";
		}
		logger.fine("Loyalty level = "+loyalty);

		if (!loyalty.equals(oldLoyalty)) try {
			logger.fine("Change in loyalty level detected");
			JsonObjectBuilder builder = Json.createObjectBuilder();

			String user = request.getRemoteUser(); //logged-in user
			if (user != null) builder.add("id", user);

			builder.add("owner", owner);
			builder.add("old", oldLoyalty);
			builder.add("new", loyalty);

			JsonObject message = builder.build();

			invokeJMS(message);
		} catch (JMSException jms) { //in case MQ is not configured, just log the exception and continue
			logger.warning("Unable to send message to JMS provider.  Continuing without notification of change in loyalty level.");
			logException(jms);
			Exception linked = jms.getLinkedException(); //get the nested exception from MQ
			if (linked != null) logException(linked);
		} catch (NamingException ne) { //in case MQ is not configured, just log the exception and continue
			logger.warning("Unable to get lookup JMS managed resources from JNDI.  Ensure your server.xml is configured correctly.");
			logException(ne);
		} catch (Throwable t) { //in case MQ is not configured, just log the exception and continue
			logException(t);
		}

		loyaltyLevel.add("owner", owner);
		loyaltyLevel.add("loyalty", loyalty);

		return loyaltyLevel.build();
	}

	/** Connect to the server, and lookup the managed resources. 
	 */
	private void initialize() throws NamingException {
		logger.fine("Getting the InitialContext");
		InitialContext context = new InitialContext();

		//lookup our JMS objects
		logger.fine("Looking up our JMS resources");
		queueCF = (QueueConnectionFactory) context.lookup(NOTIFICATION_QCF);
		queue = (Queue) context.lookup(NOTIFICATION_Q);

		initialized = true;
		logger.fine("JMS Initialization completed successfully!");
	}

	/** Send a JSON message to our notification queue.
	 */
	public void invokeJMS(JsonObject json) throws JMSException, NamingException {
		if (!initialized) initialize(); //gets our JMS managed resources (Q and QCF)

		logger.fine("Preparing to send a JMS message");

		QueueConnection connection = queueCF.createQueueConnection();
		QueueSession session = connection.createQueueSession(false, Session.AUTO_ACKNOWLEDGE);

		String contents = json.toString();
		TextMessage message = session.createTextMessage(contents);

		logger.fine("Sending "+contents+" to "+queue.getQueueName());

		//"mqclient" group needs "put" authority on the queue for next two lines to work
		QueueSender sender = session.createSender(queue);
		sender.send(message);

		sender.close();
		session.close();
		connection.close();

		logger.info("JMS Message sent successfully!");
	}

	private static void logException(Throwable t) {
		logger.warning(t.getClass().getName()+": "+t.getMessage());

		//only log the stack trace if the level has been set to at least FINE
		if (logger.isLoggable(Level.FINE)) {
			StringWriter writer = new StringWriter();
			t.printStackTrace(new PrintWriter(writer));
			logger.fine(writer.toString());
		}
	}
}
