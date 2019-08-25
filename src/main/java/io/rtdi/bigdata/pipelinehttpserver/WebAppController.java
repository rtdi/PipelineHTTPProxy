package io.rtdi.bigdata.pipelinehttpserver;

import java.io.File;
import java.io.IOException;
import java.util.ServiceLoader;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.rtdi.bigdata.connector.pipeline.foundation.IPipelineServer;
import io.rtdi.bigdata.connector.pipeline.foundation.PipelineServerAbstract;
import io.rtdi.bigdata.connector.pipeline.foundation.exceptions.PipelineRuntimeException;
import io.rtdi.bigdata.connector.pipeline.foundation.exceptions.PropertiesException;

@WebListener
public class WebAppController implements ServletContextListener {

	private static final String ERRORMESSAGE = "ERRORMESSAGE";
	private static final String API = "API";
	protected final Logger logger = LogManager.getLogger(this.getClass().getName());
	private APIController controller = null;


	public static String getError(ServletContext servletContext) {
		return (String) servletContext.getAttribute(ERRORMESSAGE);
	}

	public static PipelineServerAbstract<?, ?, ?, ?> getPipelineAPI(ServletContext servletContext) {
		return (PipelineServerAbstract<?, ?, ?, ?>) servletContext.getAttribute(API);
	}
	

	public static IPipelineServer<?, ?, ?, ?> getPipelineAPIOrFail(ServletContext servletContext) throws PipelineRuntimeException {
		IPipelineServer<?, ?, ?, ?> api = getPipelineAPI(servletContext);
		if (api == null) {
			throw new PipelineRuntimeException("Servlet does not have an API class associated");
		}
		return api;
	}


	@Override
	public void contextDestroyed(ServletContextEvent sce) {
		if (controller != null) {
			controller.interrupt();
		}
	}
	
	private static void setError(ServletContext sce, String errormessage) {
		sce.setAttribute(ERRORMESSAGE, errormessage);
	}

	@Override
	public void contextInitialized(ServletContextEvent sce) {
		try {
			ServletContext context = sce.getServletContext();
			IPipelineServer<?,?,?,?> api = null;
			
			@SuppressWarnings("rawtypes")
			ServiceLoader<IPipelineServer> loader = ServiceLoader.load(IPipelineServer.class);
			int count = 0;
			for (IPipelineServer<?,?,?,?> serv : loader) {
			    api = serv;
			    count++;
			}
			
			if (count == 0) {
				throw new PropertiesException("No class for an IPipelineServer was found. Seems a jar file is missing?");
			} else if (count != 1) {
				logger.error("More than one IPipelineServer class was found, hence might be using the wrong one \"{}\"", loader.toString());
				setError(context, "More than one IPipelineServer class was found, hence might be using the wrong one");
			}
			context.setAttribute(API, api);
			
			String configdirpath = null;
			
			try {
				InitialContext initialContext = new javax.naming.InitialContext();  
				configdirpath = (String) initialContext.lookup("java:comp/env/io.rtdi.bigdata.connectors.configpath");
				String webappname = sce.getServletContext().getServletContextName();
				if (webappname == null && configdirpath != null) { // the latter condition is not relevant as the lookup throws an exception in case it is not found
					logger.info("Tomcat context environment variable was found but the webapp has no name");
					configdirpath = null;
				} else {
					configdirpath = configdirpath + File.separatorChar + webappname;
				}
			} catch (NamingException e) {
				logger.info("Tomcat context environment variable \"io.rtdi.bigdata.connectors.configpath\" not found, using the WEB-INF dir as root for the settings", e);
			}
			if (configdirpath == null) {
				configdirpath = sce.getServletContext().getRealPath("WEB-INF") + File.separatorChar + "connector";
			}
			File configdir = new File(configdirpath);
			if (!configdir.exists()) {
				configdir.mkdirs();
				logger.info("The config root directory \"" + configdir.getAbsolutePath() + "\" does not exist - created it");
			}

			api.loadConnectionProperties(configdir);
			controller  = new APIController(api, context);
			controller.start();
		} catch (IOException e) {
			logger.error("WebApp failed to start properly", e);
			setError(sce.getServletContext(), e.getMessage());
		}
	}

	private class APIController extends Thread {
		
		private IPipelineServer<?, ?, ?, ?> api;
		private ServletContext context;

		private APIController(IPipelineServer<?, ?, ?, ?> api, ServletContext context) {
			this.api = api;
			this.context = context;
		}

		@Override
		public void run() {
			try {
				while (!isInterrupted()) {
					api.open();
					while (!isInterrupted() && api.isAlive()) {
						sleep(60000); // might trigger the InterruptedException and then the api.close() is not called
					}
					api.close();
				}				
			} catch (PropertiesException | InterruptedException e) {
				logger.error("API Controller terminated permanently", e);
				setError(context, e.getMessage());
			} finally {
				api.close();
			}
		}
		
	}
}
