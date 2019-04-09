grammar PreprocessorCommand;

command
    : action ('if' conditional)? '.' ;

action
    : INCLUDE (UNTIL END | FOLLOWING)      # include_until_action
    | INCLUDE LINE                         # include_line_action
    | REMOVE (UNTIL END | FOLLOWING)       # remove_until_action
    | REMOVE LINE                          # remove_line_action
    | INCLUDE FILE                         # include_file_action
    | REMOVE FILE                          # remove_file_action
    | INCLUDE PACKAGE                      # include_package_action
    | REMOVE PACKAGE                       # remove_package_action
    | INCLUDE? COMMENT                     # include_comment_action
    | TO_DO COMMENT?                       # include_todo_action
    | UNCOMMENT                            # uncomment_action
    | END                                  # end_action
    ;

conditional
    : NOT conditional                                           # negate_conditional
    | conditional (EQ | NE | LT | GT | LTE | GTE) conditional   # compare_op_conditional
    | conditional (OR | AND) conditional                        # boolean_op_conditional
    | VARIABLE                                                  # variable_conditional
    | LITERAL                                                   # literal_conditional
    | '(' conditional ')'                                       # parens_conditional
    ;

INCLUDE : 'include' ;
UNTIL : 'until' ;
FOLLOWING : 'following' ;
LINE : 'line' ;
FILE : 'file' ;
PACKAGE : 'package' | 'pkg' ;
REMOVE : 'remove' ;
COMMENT : 'comment' ;
UNCOMMENT : 'uncomment' ;
END : 'end' ;
TO_DO : 'to_do' | 'todo' | 'unimplemented' ;
AND : 'and' ;
OR : 'or' ;
NOT : 'not' ;
NE : '!=' ;
LTE : '<=' ;
GTE : '>=' ;
LT : '<' ;
GT : '>' ;
EQ : '=' | '==' ;

LITERAL : [0-9]+ ;
VARIABLE : '#' [a-z_\-]* ;
WS: [ \t\n\r]+ -> skip ;