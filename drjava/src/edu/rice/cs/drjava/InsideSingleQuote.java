package edu.rice.cs.drjava;

/**
 * Shadowing state that corresponds to being between single quotes.
 * @version$Id$
 */
public class InsideSingleQuote extends ReducedModelState {
  public static final InsideSingleQuote ONLY = new InsideSingleQuote();
  
  private InsideSingleQuote() {
  }
  
   /**
   * Walk function for when inside single quotes.
   *  <ol>
   *  <li> If we've reached the end of the list, return.
   *  <li> If we find //, /* or * /, split them into two separate braces.
   *       The cursor will be on the first of the two new braces.
   *  <li> If current brace = \n or ', mark current brace FREE, next(), and
   *       go to updateFree.
   *       Else, mark current brace as INSIDE_SINGLE_QUOTE, go to next brace, recur.
   * </ol>   
   */
  ReducedModelState update(TokenList.Iterator copyCursor) {
    if (copyCursor.atEnd()) {
      return STUTTER;
    }
    copyCursor._splitCurrentIfCommentBlock(true,false);
    _combineCurrentAndNextIfFind("","", copyCursor);
    _combineCurrentAndNextIfEscape(copyCursor);
    
    String type = copyCursor.current().getType();
    
    if (type.equals("\n")) {
      copyCursor.current().setState(FREE);
      copyCursor.next();
      return FREE;
    }
    else if (type.equals("\'")) {
      // make sure this is a CLOSE quote
      if (copyCursor.current().isOpen())
        copyCursor.current().flip();
      
      copyCursor.current().setState(FREE);
      copyCursor.next();
      return FREE;
    }
    else {
      copyCursor.current().setState(INSIDE_SINGLE_QUOTE);
      copyCursor.next();
      return INSIDE_SINGLE_QUOTE;
    }
  }
}