/* Generated By:JJTree: Do not edit this line. ONamedParameter.java Version 4.3 */
/* JavaCCOptions:MULTI=true,NODE_USES_PARSER=false,VISITOR=true,TRACK_TOKENS=true,NODE_PREFIX=O,NODE_EXTENDS=,NODE_FACTORY=,SUPPORT_CLASS_VISIBILITY_PUBLIC=true */
package com.orientechnologies.orient.core.sql.parser;

public class ONamedParameter extends OInputParameter {

  protected OIdentifier paramName;

  public ONamedParameter(int id) {
    super(id);
  }

  public ONamedParameter(OrientSql p, int id) {
    super(p, id);
  }

  /** Accept the visitor. **/
  public Object jjtAccept(OrientSqlVisitor visitor, Object data) {
    return visitor.visit(this, data);
  }

  @Override
  public String toString() {
    return ":" + paramName.toString();
  }
}
/* JavaCC - OriginalChecksum=8a00a9cf51a15dd75202f6372257fc1c (do not edit this line) */
