package com.orientechnologies.orient.core.sql.parser;

import com.orientechnologies.orient.core.command.OBasicCommandContext;
import com.orientechnologies.orient.core.command.OCommandContext;
import com.orientechnologies.orient.core.db.ODatabase;
import com.orientechnologies.orient.core.sql.executor.ODDLExecutionPlan;
import com.orientechnologies.orient.core.sql.executor.OInternalExecutionPlan;
import com.orientechnologies.orient.core.sql.executor.OTodoResultSet;

import java.util.HashMap;
import java.util.Map;

/**
 * Created by luigidellaquila on 12/08/16.
 */
public abstract class ODDLStatement extends OStatement {

  public ODDLStatement(int id) {
    super(id);
  }

  public ODDLStatement(OrientSql p, int id) {
    super(p, id);
  }

  public abstract OTodoResultSet executeDDL(OCommandContext ctx);

  public OTodoResultSet execute(ODatabase db, Object[] args) {
    OBasicCommandContext ctx = new OBasicCommandContext();
    ctx.setDatabase(db);
    Map<Object, Object> params = new HashMap<>();
    if (args != null) {
      for (int i = 0; i < args.length; i++) {
        params.put(i, args[i]);
      }
    }
    ctx.setInputParameters(params);
    ODDLExecutionPlan executionPlan = (ODDLExecutionPlan) createExecutionPlan(ctx);
    return executionPlan.executeInternal(ctx);
  }

  public OTodoResultSet execute(ODatabase db, Map params) {
    OBasicCommandContext ctx = new OBasicCommandContext();
    ctx.setDatabase(db);
    ctx.setInputParameters(params);
    ODDLExecutionPlan executionPlan = (ODDLExecutionPlan) createExecutionPlan(ctx);
    return executionPlan.executeInternal(ctx);
  }

  public OInternalExecutionPlan createExecutionPlan(OCommandContext ctx) {
    return new ODDLExecutionPlan(ctx, this);
  }

}