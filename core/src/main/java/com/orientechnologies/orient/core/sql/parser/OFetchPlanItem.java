/* Generated By:JJTree: Do not edit this line. OFetchPlanItem.java Version 4.3 */
/* JavaCCOptions:MULTI=true,NODE_USES_PARSER=false,VISITOR=true,TRACK_TOKENS=true,NODE_PREFIX=O,NODE_EXTENDS=,NODE_FACTORY=,SUPPORT_CLASS_VISIBILITY_PUBLIC=true */
package com.orientechnologies.orient.core.sql.parser;

import java.util.ArrayList;
import java.util.List;

public class OFetchPlanItem extends SimpleNode {

  protected Boolean      star;

  protected OInteger     leftDepth;

  protected OInteger     rightDepth;

  protected List<String> fieldChain = new ArrayList<String>();

  public OFetchPlanItem(int id) {
    super(id);
  }

  public OFetchPlanItem(OrientSql p, int id) {
    super(p, id);
  }

  /** Accept the visitor. **/
  public Object jjtAccept(OrientSqlVisitor visitor, Object data) {
    return visitor.visit(this, data);
  }

  @Override
  public String toString() {
    StringBuilder result = new StringBuilder();
    if (star) {
      result.append("*");
    } else {
      if (leftDepth != null) {
        result.append("[");
        result.append(leftDepth.toString());
        result.append("]");
      }

      boolean first = true;
      for (String s : fieldChain) {
        if (!first) {
          result.append(".");
        }
        result.append(s);
        first = false;
      }

    }
    result.append(":");
    result.append(rightDepth.toString());

    return result.toString();
  }
}
/* JavaCC - OriginalChecksum=b7f4c9a97a8f2ca3d85020e054a9ad16 (do not edit this line) */
