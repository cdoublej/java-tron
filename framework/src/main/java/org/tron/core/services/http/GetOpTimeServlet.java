package org.tron.core.services.http;

import com.alibaba.fastjson.JSON;
import org.springframework.stereotype.Component;
import org.tron.core.vm.VM;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component
public class GetOpTimeServlet extends RateLimiterServlet {

  protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    String results = JSON.toJSONString(VM.opTimeRecords);
    response.getWriter().println(results);
  }
}
