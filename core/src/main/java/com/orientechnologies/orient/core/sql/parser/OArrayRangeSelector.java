/* Generated By:JJTree: Do not edit this line. OArrayRangeSelector.java Version 4.3 */
/* JavaCCOptions:MULTI=true,NODE_USES_PARSER=false,VISITOR=true,TRACK_TOKENS=true,NODE_PREFIX=O,NODE_EXTENDS=,NODE_FACTORY=,SUPPORT_CLASS_VISIBILITY_PUBLIC=true */
package com.orientechnologies.orient.core.sql.parser;

public class OArrayRangeSelector extends SimpleNode {
  protected Integer              from;
  protected Integer              to;

  protected OArrayNumberSelector fromSelector;
  protected OArrayNumberSelector toSelector;

  public OArrayRangeSelector(int id) {
    super(id);
  }

  public OArrayRangeSelector(OrientSql p, int id) {
    super(p, id);
  }

  /** Accept the visitor. **/
  public Object jjtAccept(OrientSqlVisitor visitor, Object data) {
    return visitor.visit(this, data);
  }

  @Override
  public String toString() {
    StringBuilder result = new StringBuilder();
    if (from != null) {
      result.append(from);
    } else {
      result.append(fromSelector.toString());
    }
    result.append("..");
    if (to != null) {
      result.append(to);
    } else {
      result.append(toSelector.toString());
    }
    return result.toString();
  }
}
/* JavaCC - OriginalChecksum=594a372e31fcbcd3ed962c2260e76468 (do not edit this line) */
