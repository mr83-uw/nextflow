/*
 * Copyright (c) 2012, the authors.
 *
 *   This file is part of 'Nextflow'.
 *
 *   Nextflow is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   Nextflow is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with Nextflow.  If not, see <http://www.gnu.org/licenses/>.
 */

package nextflow.ast
import groovy.util.logging.Slf4j
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.VariableScope
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.GStringExpression
import org.codehaus.groovy.ast.expr.MapExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.ast.stmt.ReturnStatement
import org.codehaus.groovy.ast.stmt.Statement
import org.codehaus.groovy.control.CompilePhase
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.syntax.SyntaxException
import org.codehaus.groovy.transform.ASTTransformation
import org.codehaus.groovy.transform.GroovyASTTransformation
/**
 * Implement some syntax sugars of Nextflow DSL scripting.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */

@Slf4j
@GroovyASTTransformation(phase = CompilePhase.CONVERSION)
class ProcessDefTransformImpl implements ASTTransformation {

    def String currentTaskName

    def String currentLabel

    @Override
    void visit(ASTNode[] astNodes, SourceUnit unit) {

        createVisitor(unit).visitClass(astNodes[1])

    }

    /*
     * create the code visitor
     */
    def createVisitor( SourceUnit unit ) {

        new ClassCodeVisitorSupport() {


            protected SourceUnit getSourceUnit() { unit }

            void visitMethodCallExpression(MethodCallExpression methodCall) {

                // pre-condition to be verified to apply the transformation
                Boolean preCondition = methodCall.with {
                    (getMethod() instanceof ConstantExpression && objectExpression?.getText() == 'this')
                }

                /*
                 * intercept the *process* method in order to transform the script closure
                 */
                if( preCondition &&  methodCall.getMethodAsString() == 'process' ) {

                    // clear block label
                    currentLabel = null
                    // keep track of 'process' method (it may be removed)
                    currentTaskName = methodCall.getMethodAsString()
                    try {
                        convertProcessDef(methodCall,sourceUnit)
                        super.visitMethodCallExpression(methodCall)
                    }
                    finally {
                        currentTaskName = null
                    }
                }

                // just apply the default behavior
                else {
                    super.visitMethodCallExpression(methodCall)
                }

            }
        }
    }



    /**
     * This transformation applies to tasks code block and translate
     * the script string to be executed wrapping it by a closure
     * <p>
     *     For example:
     * <pre>
     *     task {
     *         input x: someChannel
     *         output 'resultFile'
     *
     *         "do this; do that"
     *     }
     *
     * </pre>
     * becomes:
     *
     * <pre>
     *     task {
     *         input x: someChannel
     *         output 'resultFile'
     *
     *         { -> "do this; do that" }
     *     }
     *
     * </pre>
     *
     * @param methodCall
     * @param unit
     */
    def void convertProcessBlock( MethodCallExpression methodCall, SourceUnit unit ) {
        log.trace "Apply task closure transformation to method call: $methodCall"

        def args = methodCall.arguments as ArgumentListExpression
        def lastArg = args.expressions.size()>0 ? args.getExpression(args.expressions.size()-1) : null
        def isClosure = lastArg instanceof ClosureExpression

        if( isClosure ) {
            // the block holding all the statements defined in the process (closure) definition
            def block = (lastArg as ClosureExpression).code as BlockStatement
            def len = block.statements.size()

            /*
             * iterate over the list of statements to:
             * - converts the method after the 'input:' label as input parameters
             * - converts the method after the 'output:' label as output parameters
             * - collect all the statement after the 'exec:' label
             */
            List<Statement> execStatements = []
            def iterator = block.getStatements().iterator()
            while( iterator.hasNext() ) {

                // get next statement
                Statement stm = iterator.next()

                // keep track of current block label
                currentLabel = stm.statementLabel ?: currentLabel

                switch(currentLabel) {
                    case 'input':
                        if( stm instanceof ExpressionStatement ) {
                            convertInputMethod( stm.getExpression() )
                        }
                        break

                    case 'output':
                        if( stm instanceof ExpressionStatement ) {
                            convertOutputMethod( stm.getExpression() )
                        }
                        break

                    case 'share':
                        if( stm instanceof ExpressionStatement ) {
                            convertShareMethod( stm.getExpression() )
                        }
                        break

                    case 'exec':
                        iterator.remove()
                        execStatements << stm
                        break

                    case 'script':
                        iterator.remove()
                        execStatements << stm
                        break

                }
            }

            /*
             * wrap all the statements after the 'exec:'  label by a new closure containing them (in a new block)
             */
            boolean done = false
            int line = methodCall.lineNumber
            int coln = methodCall.columnNumber
            if( execStatements ) {
                // create a new Closure
                def execBlock = new BlockStatement(execStatements, new VariableScope(block.variableScope))
                def execClosure = new ClosureExpression( Parameter.EMPTY_ARRAY, execBlock )

                // append the new block to the
                block.addStatement( new ExpressionStatement(execClosure) )
                done = true

            }

            /*
             * when the last statement is a string script, the 'script:' label can be omitted
             */
            else if( len ) {
                def stm = block.getStatements().get(len-1)

                if ( stm instanceof ReturnStatement  ){
                    (done,line,coln) = wrapExpressionWithClosure(block, stm.expression, len)
                }

                else if ( stm instanceof ExpressionStatement )  {
                    (done,line,coln) = wrapExpressionWithClosure(block, stm.expression, len)
                }
                // set the 'script' flag
                currentLabel = 'script'
            }

            // set the 'script' flag parameter
            def flag = currentLabel == 'script' ? ConstantExpression.PRIM_TRUE : ConstantExpression.PRIM_FALSE
            args.getExpressions().add( args.expressions.size()-1, flag )

            if (!done) {
                log.trace "Invalid 'process' definition -- Process must terminate with string expression"
                unit.addError( new SyntaxException("Not a valid process definition -- Make sure process ends with the script to be executed wrapped by quote characters", line,coln))
            }
        }
    }

    /*
     * handle *input* parameters
     */
    def void convertInputMethod( Expression expression ) {
        log.trace "convert > input expression: $expression"

        if( !(expression instanceof MethodCallExpression) ) {
            return
        }

        def methodCall = expression as MethodCallExpression
        def methodName = methodCall.getMethodAsString()
        log.trace "convert > input method: $methodName"

        if( methodName in ['val','env','file','each'] ) {
            //this methods require a special prefix
            methodCall.setMethod( new ConstantExpression('_in_' + methodName) )
            // the following methods require to replace a variable reference to a constant
            convertVarToConst(methodCall,true)
        }

        else if( methodName == '_as'  ) {
            convertVarToConst(methodCall)
        }
        else if( methodName == 'stdin' )  {
            convertVarToConst(methodCall, true)
        }


        if( methodCall.objectExpression instanceof MethodCallExpression ) {
            convertInputMethod(methodCall.objectExpression)
        }

    }


    /*
     * handle *shared* parameters
     */

    static SHARE_METHOD_MAP = [val:'_share_val', file: '_share_file']

    def void convertShareMethod( Expression expression ) {
        log.trace "convert > shared expression: $expression"

        if( !(expression instanceof MethodCallExpression) ) {
            return
        }

        def methodCall = expression as MethodCallExpression
        def methodName = methodCall.getMethodAsString()
        log.trace "convert > shared method: $methodName"

        if( SHARE_METHOD_MAP.containsKey(methodName) ) {
            methodCall.setMethod( new ConstantExpression( SHARE_METHOD_MAP[methodName] ) )
         }

        if( methodName in ['to','_as'] ) {
            convertVarToConst(methodCall)
        }
        else if( methodName in ['file','val']  ) {
            convertVarToConst(methodCall, true)
        }

        if( methodCall.objectExpression instanceof MethodCallExpression ) {
            convertShareMethod(methodCall.objectExpression)
        }
    }


    def void convertOutputMethod( Expression expression ) {
        log.trace "convert > output expression: $expression"

        if( !(expression instanceof MethodCallExpression) ) {
            return
        }

        def methodCall = expression as MethodCallExpression
        def methodName = methodCall.getMethodAsString()
        log.trace "convert > output method: $methodName"

        if( methodName in ['val','file'] ) {
            // prefix the method name with the string '_out_'
            methodCall.setMethod( new ConstantExpression('_out_' + methodName) )
        }

        if( methodName in ['val','file','to','stdout'] ) {
            convertVarToConst(methodCall)
        }

        // continue to traverse
        if( methodCall.objectExpression instanceof MethodCallExpression ) {
            convertOutputMethod(methodCall.objectExpression)
        }

    }

    /**
     * This method converts the a method call argument from a Variable to a Constant value
     * so that it is possible to reference variable that not yet exist
     *
     * @param methodCall The method object for which it is required to change args definition
     * @param flagVariable Whenever append a flag specified if the variable replacement has been applied
     * @param index The index of the argument to modify
     * @return
     */
    private List<Expression> convertVarToConst( MethodCallExpression methodCall, boolean flagVariable=false, int index = 0 ) {

        def args = methodCall.getArguments() as ArgumentListExpression

        int i = 0
        List<Expression> newArgs = []
        for( Expression expr : args )  {

            if( index == i++ ) {
                def isVariable = expr instanceof VariableExpression
                // prefix the argument with a boolean to specify
                // whether it is variable or a literal value
                if( flagVariable ) {
                    newArgs << ( isVariable ? ConstantExpression.PRIM_TRUE : ConstantExpression.PRIM_FALSE )
                }

                if( isVariable ) {
                    // when it is a variable expression, replace it with a constant rapresenting
                    // the variable name
                    newArgs << new ConstantExpression( ((VariableExpression) expr).getName() )
                    continue
                }
            }

            newArgs << expr

        }


        methodCall.setArguments(new ArgumentListExpression(newArgs))

        return newArgs
    }


    def List wrapExpressionWithClosure( BlockStatement block, Expression expr, int len ) {
        if( expr instanceof GStringExpression || expr instanceof ConstantExpression ) {
            // remove the last expression
            block.statements.remove(len-1)

            // and replace it by a wrapping closure
            def closureExp = new ClosureExpression( Parameter.EMPTY_ARRAY, new ExpressionStatement(expr) )
            closureExp.variableScope = new VariableScope()
            closureExp.variableScope.parent = block.variableScope

            // append to the list of statement
            block.statements.add( new ExpressionStatement(closureExp) )

            return [true,0,0]
        }
        else if( expr instanceof ClosureExpression ) {
            // do not touch it
            return [true,0,0]
        }
        else {
            log.trace "Invalid process result expression: ${expr} -- Only constant or string expression can be used"
        }

        return [false, expr.lineNumber, expr.columnNumber]
    }

    /**
     * This method handle the process definition, so that it transform the user entered syntax
     *    process myName ( named: args, ..  ) { code .. }
     *
     * into
     *    process ( [named:args,..], String myName )  { }
     *
     * @param methodCall
     * @param unit
     */
    def void convertProcessDef( MethodCallExpression methodCall, SourceUnit unit ) {
        log.trace "Converts 'process' ${methodCall.arguments} "

        assert methodCall.arguments instanceof ArgumentListExpression
        def list = (methodCall.arguments as ArgumentListExpression).getExpressions()

        // extract the first argument which has to be a method-call expression
        // the name of this method represent the *process* name
        assert list.size() == 1
        assert list[0] instanceof MethodCallExpression
        def nested = list[0] as MethodCallExpression
        def name = nested.getMethodAsString()

        // the nested method arguments are the arguments to be passed
        // to the process definition, plus adding the process *name*
        // as an extra item in the arguments list
        def args = nested.getArguments() as ArgumentListExpression
        log.trace "Process name: $name with args: $args"

        // make sure to add the 'name' after the map item
        // (which represent the named parameter attributes)
        list = args.getExpressions()
        if( list.size()>0 && list[0] instanceof MapExpression ) {
            list.add(1, new ConstantExpression(name))
        }
        else {
            list.add(0, new ConstantExpression(name))
        }

        // set the new list as the new arguments
        methodCall.setArguments( args )

        // now continue as before !
        convertProcessBlock(methodCall, unit)
    }

}
