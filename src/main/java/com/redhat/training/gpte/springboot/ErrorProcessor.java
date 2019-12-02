package com.redhat.training.gpte.springboot;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;

public class ErrorProcessor implements Processor {
    public void process(Exchange exchange) throws Exception {
    	  Exception cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
    	  exchange.getIn().setHeader("error-message", cause.getMessage());
    	  exchange.getIn().setHeader("error-code", "111");
	      exchange.getIn().setHeader("message", cause.getMessage());
    }
}