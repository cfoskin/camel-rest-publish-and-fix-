/*
 * Copyright 2016 Red Hat, Inc.
 * <p>
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 */
package com.redhat.training.gpte.springboot;

import org.acme.Customer;
import org.apache.activemq.jms.pool.PooledConnectionFactory;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePattern;
import org.apache.camel.LoggingLevel;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.amqp.AMQPComponent;
import org.apache.camel.component.servlet.CamelHttpTransportServlet;
import org.apache.camel.dataformat.bindy.csv.BindyCsvDataFormat;
import org.apache.camel.model.dataformat.CsvDataFormat;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.apache.qpid.jms.JmsConnectionFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportResource;

@SpringBootApplication
// load regular Spring XML file from the classpath that contains the Camel XML DSL
@ImportResource({"classpath:spring/camel-context.xml"})
public class Application extends RouteBuilder {

    /**
     * A main method to start this application.
     */
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
    
    @Override
    public void configure() throws Exception {
		 BindyCsvDataFormat bindy = new BindyCsvDataFormat(Customer.class);
         bindy.setLocale("default");
         
         onException(IllegalArgumentException.class)
 	    .log(LoggingLevel.INFO, "org.fuse.usecase", "Fail")
         .to("direct:error")
         .handled(true);
         
         restConfiguration().component("servlet").host("localhost").port(8080);
		 
        //Route 1
 	  	rest("/service")
         .post("/customers")
         .to("amqp:queue:inputQueue");

        //Route 2
        from("amqp:queue:inputQueue")
        	.setExchangePattern(ExchangePattern.InOnly)
          .split()
          .tokenize(";")
  	      .log(LoggingLevel.INFO, "org.fuse.usecase", "Tokenized")
          .unmarshal(bindy)
    	  .log(LoggingLevel.INFO, "org.fuse.usecase", "Unmarshalled")
          .to("dozer:Account?mappingFile=transformation.xml&sourceModel=org.acme.Customer&targetModel=org.globex.Account")
          .marshal().json(JsonLibrary.Jackson)
    	  .log(LoggingLevel.INFO, "org.fuse.usecase", "Marshalled - Sending to Account Queue")
          .to("amqp:queue:accountQueue")
          .end()
          .transform(simple("Processed the customer datas"));
		
		from("direct:error")
	    .log(LoggingLevel.INFO, "org.fuse.usecase", "Send Errors to Error Topic")
		.process(new ErrorProcessor())
        .to("amqp:topic:error:errorNotification");
        
        //Route 3
        from("amqp:topic:error:errorNotification")
	    .log(LoggingLevel.INFO, "org.fuse.usecase", "Send Errors to DBs")
		.setExchangePattern(ExchangePattern.InOnly)
		.to("sql:insert into USECASE.T_ERROR (ERROR_CODE, ERROR_MESSAGE, MESSAGE, STATUS) VALUES (:#${headers.error-code}, :#${headers.error-message}, :#${body}, 'ERROR')");
	  	
        //Route 4
        from("sql:select MESSAGE, ID from USECASE.T_ERROR where STATUS = 'FIXED' ?consumer.onConsume=update USECASE.T_ERROR set STATUS='CLOSED' where ID = :#ID")
        .split(body())
        .log(LoggingLevel.INFO, "org.fuse.usecase", "${body[message]}")
        .setBody().simple("${body[message]}")
        .to("amqp:queue:inputQueue");
    }
    
    @Bean
    ServletRegistrationBean servletRegistrationBean() {
        ServletRegistrationBean servlet = new ServletRegistrationBean(
            new CamelHttpTransportServlet(), "/rest/*");
        servlet.setName("CamelServlet");
        return servlet;
    }

    @Bean(name = "amqp-component")
    AMQPComponent amqpComponent(AMQPConfiguration config) {
        JmsConnectionFactory qpid = new JmsConnectionFactory(config.getUsername(), config.getPassword(), "amqp://"+ config.getHost() + ":" + config.getPort());
        //qpid.setTopicPrefix("topic://");

        PooledConnectionFactory factory = new PooledConnectionFactory();
        factory.setConnectionFactory(qpid);

        return new AMQPComponent(factory);
    }
}