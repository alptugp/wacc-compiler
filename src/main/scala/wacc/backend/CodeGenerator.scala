package wacc.backend

import wacc.AST._
import scala.collection.mutable

object CodeGenerator {
  // Compiles the program
  def compileProgram(programNode: Program, codeGenState: CodeGeneratorState)(
      implicit instructions: mutable.ListBuffer[Instruction]
  ): CodeGeneratorState = {
    var newCodeGenState = codeGenState

    // Compiles each function declaration in the program
    programNode.funcs.foreach(func =>
      newCodeGenState = newCodeGenState.addFunctionName(func.ident.name)
    )
    programNode.funcs.foreach(func => compileFunc(func, newCodeGenState))

    instructions.addAll(
      List(
        Label("main"),
        Push(LinkRegister)
      )
    )
    newCodeGenState = newCodeGenState.copy(stackPointerOffset =
      newCodeGenState.stackPointerOffset + 4
    )

    // Compiles each statement in the program
    programNode.stat.foreach(stat =>
      newCodeGenState = compileStatWithNewScope(stat, newCodeGenState)
    )

    instructions.addAll(
      List(
        // Set exit code as 0
        Load(R0, LoadImmVal(0)),
        Pop(ProgramCounter),
        Directive(".ltorg")
      )
    )

    newCodeGenState.copy(stackPointerOffset =
      newCodeGenState.stackPointerOffset - 4
    )
  }

  // Compiles function declaration
  def compileFunc(funcNode: Func, codeGenState: CodeGeneratorState)(implicit
      instructions: mutable.ListBuffer[Instruction]
  ): CodeGeneratorState = {
    var newCodeGenState = codeGenState

    // Compile each of the parameters in the function declaration
    funcNode.paramList.foreach(param =>
      newCodeGenState = compileParam(param, newCodeGenState)
    )

    instructions.addAll(
      List(
        Label("wacc_" + funcNode.ident.name),
        Push(LinkRegister)
      )
    )
    newCodeGenState = newCodeGenState.copy(stackPointerOffset =
      newCodeGenState.stackPointerOffset + 4
    )

    // Needed for restoring stack pointer after compiling the body of the function declaration
    val newIdentToOffset =
      newCodeGenState.identToOffset + ("originalSP" -> newCodeGenState.stackPointerOffset)
    newCodeGenState = newCodeGenState.copy(identToOffset = newIdentToOffset)

    // Compile each of the statements in the function declaration's body
    funcNode.stats.foreach(stat =>
      newCodeGenState = compileStatWithNewScope(stat, newCodeGenState)
    )

    newCodeGenState = newCodeGenState.copy(identToOffset =
      newCodeGenState.identToOffset - "originalSP"
    )

    instructions.addAll(
      List(
        Pop(ProgramCounter),
        Directive(".ltorg")
      )
    )

    val newStackPointerOffset =
      newCodeGenState.stackPointerOffset - newCodeGenState.usedStackSize - 4
    codeGenState.copy(
      usedStackSize = 0,
      stackPointerOffset = newStackPointerOffset
    )
  }

  // Compiles function parameter
  def compileParam(
      paramNode: Param,
      codeGenState: CodeGeneratorState
  ): CodeGeneratorState = {
    val newIdentToOffset =
      codeGenState.identToOffset + (paramNode.ident.name -> (paramNode.ty.size + codeGenState.stackPointerOffset))
    val newUsedStackSize = codeGenState.usedStackSize + paramNode.ty.size
    val newStackPointerOffset =
      codeGenState.stackPointerOffset + paramNode.ty.size

    codeGenState.copy(
      identToOffset = newIdentToOffset,
      usedStackSize = newUsedStackSize,
      stackPointerOffset = newStackPointerOffset
    )
  }

  // Compiles function call
  def compileFunctionCall(funcCallNode: Call, codeGenState: CodeGeneratorState)(
      implicit instructions: mutable.ListBuffer[Instruction]
  ): CodeGeneratorState = {
    var newCodeGenState = codeGenState
    val argsSize =
      funcCallNode.args.foldLeft(0)((sum: Int, expr: Expr) => sum + expr.size)
    val resReg = newCodeGenState.getResReg

    // Compile each of the arguments
    funcCallNode.args.foreach(arg =>
      newCodeGenState = compileExpression(arg, newCodeGenState)
    )

    instructions.addAll(
      List(
        // Navigate to function
        funcCallNode.x.name match {
          case "" => {
            instructions.addAll(
              List(
                AddInstr(
                  resReg,
                  StackPointer,
                  ImmVal(
                    // Get where the function's name in the stack is stored relative to the stack pointer
                    newCodeGenState.stackPointerOffset - newCodeGenState
                      .getIdentOffset(funcCallNode.x.name)
                  )
                ),
                Load(resReg, OffsetMode(resReg))
              )
            )
            BranchAndLinkReg(resReg)
          }
          case _ => BranchAndLink("wacc_" + funcCallNode.x.name)
        },
        // Set the stack pointer back to its original value
        AddInstr(StackPointer, StackPointer, ImmVal(argsSize)),
        // Move result to result register
        Move(resReg, R0)
      )
    )

    val newStackPointerOffset = newCodeGenState.stackPointerOffset - argsSize
    val newAvailableRegs = newCodeGenState.availableRegs.tail
    newCodeGenState.copy(
      availableRegs = newAvailableRegs,
      stackPointerOffset = newStackPointerOffset
    )
  }

  def compileExpression(exprNode: Expr, codeGenState: CodeGeneratorState)(
      implicit instructions: mutable.ListBuffer[Instruction]
  ): CodeGeneratorState = {
    var newCodeGenState = codeGenState
    val resReg = newCodeGenState.getResReg
    val operand1Reg = newCodeGenState.getResReg

    exprNode match {
      // Case for binary expressions
      case Add(_, _) | Sub(_, _) | And(_, _) | Or(_, _) | LT(_, _) | LTE(_, _) |
          GT(_, _) | GTE(_, _) | Equal(_, _) | NotEqual(_, _) => {
        val operand2Reg = newCodeGenState.getNonResReg

        /* Compile the first and second expression in the binary expression,
           and add the corresponding instructions needed to instructions list */
        exprNode match {
          case Add(x, y) => {
            newCodeGenState = compileExpression(x, newCodeGenState)
            newCodeGenState = compileExpression(y, newCodeGenState)
            instructions += AddInstr(resReg, operand1Reg, operand2Reg)
          }
          case And(x, y) => {
            newCodeGenState = compileExpression(x, newCodeGenState)
            newCodeGenState = compileExpression(y, newCodeGenState)
            instructions += AndInstr(resReg, operand1Reg, operand2Reg)
          }
          case Or(x, y) => {
            newCodeGenState = compileExpression(x, newCodeGenState)
            newCodeGenState = compileExpression(y, newCodeGenState)
            instructions += OrrInstr(resReg, operand1Reg, operand2Reg)
          }
          case Sub(x, y) => {
            newCodeGenState = compileExpression(x, newCodeGenState)
            newCodeGenState = compileExpression(y, newCodeGenState)
            instructions += SubInstr(resReg, operand1Reg, operand2Reg)
          }

          case LT(x, y) => {
            newCodeGenState = compileExpression(x, newCodeGenState)
            newCodeGenState = compileExpression(y, newCodeGenState)
            instructions.addAll(
              List(
                Cmp(operand1Reg, operand2Reg),
                Move(resReg, ImmVal(1), Condition.LT),
                Move(resReg, ImmVal(0), Condition.GE)
              )
            )
          }

          case LTE(x, y) => {
            newCodeGenState = compileExpression(x, newCodeGenState)
            newCodeGenState = compileExpression(y, newCodeGenState)
            instructions.addAll(
              List(
                Cmp(operand1Reg, operand2Reg),
                Move(resReg, ImmVal(1), Condition.LE),
                Move(resReg, ImmVal(0), Condition.GT)
              )
            )
          }

          case GT(x, y) => {
            newCodeGenState = compileExpression(x, newCodeGenState)
            newCodeGenState = compileExpression(y, newCodeGenState)
            instructions.addAll(
              List(
                Cmp(operand1Reg, operand2Reg),
                Move(resReg, ImmVal(1), Condition.GT),
                Move(resReg, ImmVal(0), Condition.LE)
              )
            )
          }

          case GTE(x, y) => {
            newCodeGenState = compileExpression(x, newCodeGenState)
            newCodeGenState = compileExpression(y, newCodeGenState)
            instructions.addAll(
              List(
                Cmp(operand1Reg, operand2Reg),
                Move(resReg, ImmVal(1), Condition.GE),
                Move(resReg, ImmVal(0), Condition.LT)
              )
            )
          }

          case NotEqual(x, y) => {
            newCodeGenState = compileExpression(x, newCodeGenState)
            newCodeGenState = compileExpression(y, newCodeGenState)
            instructions.addAll(
              List(
                Cmp(operand1Reg, operand2Reg),
                Move(resReg, ImmVal(1), Condition.NE),
                Move(resReg, ImmVal(0), Condition.EQ)
              )
            )
          }

          case Equal(x, y) => {
            newCodeGenState = compileExpression(x, newCodeGenState)
            newCodeGenState = compileExpression(y, newCodeGenState)
            instructions.addAll(
              List(
                Cmp(operand1Reg, operand2Reg),
                Move(resReg, ImmVal(1), Condition.EQ),
                Move(resReg, ImmVal(0), Condition.NE)
              )
            )
          }

          case _ =>
        }

        // Register for operand2 is now available for use
        val newAvailableRegs = operand2Reg +: newCodeGenState.availableRegs
        newCodeGenState.copy(availableRegs = newAvailableRegs)
      }

      // Case for unary expressions
      case Not(_) | Neg(_) | Len(_) | Ord(_) | Chr(_) => {
        /* Compile the first expression in the unary expression,
         and add add the corresponding instructions needed to instructions list */
        exprNode match {
          case Not(x) => {
            newCodeGenState = compileExpression(x, newCodeGenState)
            instructions += XorInstr(resReg, resReg, ImmVal(1))
          }
          case Neg(x) => {
            newCodeGenState = compileExpression(x, newCodeGenState)
            instructions += Rsb(resReg, resReg, ImmVal(0))
          }
          case Len(x) => {
            newCodeGenState = compileExpression(x, newCodeGenState)
            instructions += Load(resReg, OffsetMode(resReg))
          }
          case Ord(x) => newCodeGenState = compileExpression(x, newCodeGenState)
          case Chr(x) => newCodeGenState = compileExpression(x, newCodeGenState)
        }

        newCodeGenState
      }

      case _ => newCodeGenState
    }

    newCodeGenState
  }

  def compileStatWithNewScope(statNode: Stat, codeGenState: CodeGeneratorState)(
      implicit instructions: mutable.ListBuffer[Instruction]
  ): CodeGeneratorState = {
    // TODO
    codeGenState
  }
}
