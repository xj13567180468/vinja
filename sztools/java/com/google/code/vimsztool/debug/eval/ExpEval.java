package com.google.code.vimsztool.debug.eval;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.antlr.runtime.ANTLRStringStream;
import org.antlr.runtime.CommonTokenStream;
import org.antlr.runtime.tree.CommonTree;

import com.google.code.vimsztool.debug.Debugger;
import com.google.code.vimsztool.debug.SuspendThreadStack;
import com.google.code.vimsztool.exception.ExpressionEvalException;
import com.google.code.vimsztool.parser.JavaLexer;
import com.google.code.vimsztool.parser.JavaParser;
import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.ArrayType;
import com.sun.jdi.BooleanType;
import com.sun.jdi.ClassNotLoadedException;
import com.sun.jdi.ClassType;
import com.sun.jdi.Field;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.InterfaceType;
import com.sun.jdi.InvalidTypeException;
import com.sun.jdi.InvocationException;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveType;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Type;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;

public class ExpEval {
	
	private static String[] primitiveTypeNames = { "boolean", "byte", "char",
		"short", "int", "long", "float", "double" };


	public static void main(String[] args) {
		char a = '9';
		
		int b = 97;
		char c = 'z';
		
		int j = c;
		
		System.out.print(j);
		//System.out.println((int)a);
		//System.out.print(a + c);
	}

	public static String eval(String exp) {
		try {
			JavaLexer lex = new JavaLexer(new ANTLRStringStream(exp));
			CommonTokenStream tokens = new CommonTokenStream(lex);
			JavaParser parser = new JavaParser(tokens);
			CommonTree tree = (CommonTree) parser.expression().getTree();
			printTree(tree,0);
			System.out.println("===================================");
			Object result = evalTreeNode(tree);
			if (result == null)
				return null;
			return result.toString();
		} catch (Throwable e) {
			e.printStackTrace();
			return "error in eval expression.";
		}

	}



	public static Object evalTreeNode(CommonTree node) {
		
		System.out.println(node.getText());
		CommonTree subNode = null;
		CommonTree leftOp = null;
		CommonTree rightOp = null;
		
		switch (node.getType()) {
		
		case JavaParser.PARENTESIZED_EXPR:
		case JavaParser.EXPR:
			subNode = (CommonTree) node.getChild(0);
			return evalTreeNode(subNode);
			
		case JavaParser.LOGICAL_NOT:
			subNode = (CommonTree) node.getChild(0);
			Object value = evalTreeNode(subNode);
			return ! (Boolean)value;
			
		case JavaParser.IDENT:
			return evalJdiVar(node.getText());
		case JavaParser.METHOD_CALL:
			return evalJdiInvoke(node);
			
		case JavaParser.PLUS:
		case JavaParser.MINUS:
		case JavaParser.STAR:
		case JavaParser.DIV:
			
		case JavaParser.EQUAL:
		case JavaParser.NOT_EQUAL:
			
		case JavaParser.GREATER_THAN:
		case JavaParser.GREATER_OR_EQUAL:
		case JavaParser.LESS_THAN:
		case JavaParser.LESS_OR_EQUAL:
			
		case JavaParser.LOGICAL_AND:
		case JavaParser.LOGICAL_OR:
			
		case JavaParser.AND:
		case JavaParser.OR:
			leftOp = (CommonTree) node.getChild(0);
			rightOp = (CommonTree) node.getChild(1);
			return evalTreeNode(leftOp,rightOp,node.getType());
			
		case JavaParser.DECIMAL_LITERAL :
			return Integer.valueOf(node.getText());
		case JavaParser.FLOATING_POINT_LITERAL:
			return Float.valueOf(node.getText());
		case JavaParser.CHARACTER_LITERAL:
			return Character.valueOf(node.getText().charAt(1));
		case JavaParser.STRING_LITERAL:
			return node.getText();
			
		default:
			return null;
		}
	}
	
	private static  Object evalTreeNode(CommonTree leftOp, CommonTree rightOp, int opType) {
		Object leftValue = evalTreeNode(leftOp);
		Object rightValue = evalTreeNode(rightOp);
		Object result = null;
		switch (opType) {
		
		case JavaParser.PLUS:
			return Plus.operate(leftOp, rightOp);
		case JavaParser.MINUS:
			return Minus.operate(leftOp, rightOp);
		case JavaParser.STAR:
			return Multi.operate(leftOp, rightOp);
		case JavaParser.DIV:
			return Divide.operate(leftOp, rightOp);
			
		case JavaParser.NOT_EQUAL:
			return NotEqual.operate(leftOp, rightOp);
		case JavaParser.EQUAL:
			return Equal.operate(leftOp, rightOp);
		case JavaParser.GREATER_THAN:
			return Greater.operate(leftOp, rightOp);
		case JavaParser.GREATER_OR_EQUAL:
			return GreaterOrEqual.operate(leftOp, rightOp);
		case JavaParser.LESS_THAN:
			return Less.operate(leftOp, rightOp);
		case JavaParser.LESS_OR_EQUAL:
			return LessOrEqual.operate(leftOp, rightOp);
			
		case JavaParser.LOGICAL_AND:
			return LogicalAnd.operate(leftOp, rightOp);
		case JavaParser.LOGICAL_OR:
			return LogicalOr.operate(leftOp, rightOp);
		case JavaParser.AND:
			result = ((Integer) leftValue) & ((Integer) rightValue);
			break;
		case JavaParser.OR:
			result = ((Integer) leftValue) | ((Integer) rightValue);
			break;
		}
		return result;
	}
	
	
	public static void printTree(CommonTree t, int indent) {
        if ( t != null ) {
            StringBuffer sb = new StringBuffer(indent);
            for ( int i = 0; i < indent; i++ )
                sb = sb.append("   ");
            for ( int i = 0; i < t.getChildCount(); i++ ) {
                System.out.println(sb.toString() + t.getChild(i).toString());
                printTree((CommonTree)t.getChild(i), indent+1);
            }
        }
    }
	
	private static Value evalJdiInvoke(CommonTree node) {
		CommonTree dotNode = (CommonTree)node.getChild(0);
		CommonTree argNode = (CommonTree)node.getChild(1);
		int argCount = argNode.getChildCount();
		List<Value> arguments = new ArrayList<Value>();
		
		if (argCount > 0 ) {
			for (int i=0; i<argCount; i++) {
				Object result = evalTreeNode((CommonTree)argNode.getChild(i));
				arguments.add(getMirrorValue(result));
			}
			
		}
				
		Object var = evalTreeNode((CommonTree)dotNode.getChild(0));
		String methodName = dotNode.getChild(1).getText();
		if (var instanceof ObjectReference) {
			return invoke(var,methodName,arguments);
		}
		return null;
	}
	
	private static Value getMirrorValue(Object value) {
		if (value == null) return null;
		if (value instanceof Value) return (Value)value;
		
		Debugger debugger = Debugger.getInstance();
		VirtualMachine vm = debugger.getVm();
		if (value instanceof Integer) {
			return vm.mirrorOf(((Integer)value).intValue());
		} else if (value instanceof Boolean) {
			return vm.mirrorOf(((Boolean)value).booleanValue());
		} else if (value instanceof Float) {
			return vm.mirrorOf(((Float)value).floatValue());
		} else if (value instanceof Byte) {
			return vm.mirrorOf(((Byte)value).byteValue());
		} else if (value instanceof Character) {
			return vm.mirrorOf(((Character)value).charValue());
		} else if (value instanceof Double) {
			return vm.mirrorOf(((Double)value).doubleValue());
		} else if (value instanceof Long) {
			return vm.mirrorOf(((Long)value).longValue());
		} else if (value instanceof Short) {
			return vm.mirrorOf(((Short)value).shortValue());
		} else if (value instanceof String) {
			return vm.mirrorOf(((String)value));
		}
		return null;
	}
	
	private static Value evalJdiVar(String name) {
		ThreadReference threadRef = checkAndGetCurrentThread();
		try {
			SuspendThreadStack threadStack = SuspendThreadStack.getInstance();
			StackFrame stackFrame = threadRef.frame(threadStack.getCurFrame());
			ObjectReference thisObj = stackFrame.thisObject();
			Value value = findValueInFrame(threadRef, name, thisObj);
			return value;
		} catch (IncompatibleThreadStateException e) {
			throw new ExpressionEvalException("eval expression error, caused by : " + e.getMessage());
		}
	}
	private static ThreadReference checkAndGetCurrentThread() {
		Debugger debugger = Debugger.getInstance();
		if (debugger.getVm() == null ) {
			throw new ExpressionEvalException("no virtual machine connected.");
		}
		
		SuspendThreadStack threadStack = SuspendThreadStack.getInstance();
		ThreadReference threadRef = threadStack.getCurThreadRef();
		if (threadRef == null ) {
			throw new ExpressionEvalException("no suspend thread.");
		}
		return threadRef;
	}
	

	public static Value findValueInFrame(ThreadReference threadRef, String name,
			ObjectReference thisObj)  {
		
		Value value = null;
		try {
			SuspendThreadStack threadStack = SuspendThreadStack.getInstance();
			StackFrame stackFrame = threadRef.frame(threadStack.getCurFrame());
			
			LocalVariable localVariable;
			localVariable = stackFrame.visibleVariableByName(name);
			if (localVariable != null) {
				return stackFrame.getValue(localVariable);
			}
			
			ReferenceType refType = stackFrame.location().declaringType();
			if (thisObj != null ) {
				refType = thisObj.referenceType();
			}
			Field field = refType.fieldByName(name);
			if (field == null ) {
				throw new ExpressionEvalException("eval expression error, field '" + name +"' can't be found."); 
			}
			if (thisObj != null) {
				value = thisObj.getValue(field);
			} else {
				value = refType.getValue(field);
			}
		} catch (IncompatibleThreadStateException e) {
			throw new ExpressionEvalException("eval expression error, caused by:" + e.getMessage());
		} catch (AbsentInformationException e) {
			throw new ExpressionEvalException("eval expression error, caused by:" + e.getMessage());
		}
		return value;
	}
	
	public static Value invoke(Object invoker, String methodName, List args) {
		SuspendThreadStack threadStack = SuspendThreadStack.getInstance();
		ThreadReference threadRef = threadStack.getCurThreadRef();
		Value value = null;
		Method matchedMethod = null;
		List<Method> methods = null;
		ClassType refType = null;
		ObjectReference obj  = null;
		if (invoker instanceof ClassType) {
			refType = (ClassType)invoker;
		    methods = refType.methodsByName(methodName);
		} else {
		   obj = (ObjectReference)invoker;
		   methods = obj.referenceType().methodsByName(methodName);
		}
		if (methods == null || methods.size() == 0) {
			throw new ExpressionEvalException("eval expression error, method '" + methodName + "' can't be found");
		}
		if (methods.size() == 1) {
			matchedMethod = methods.get(0);
		} else {
			matchedMethod = findMatchedMethod(methods, args);
		}
		try {
		    if (invoker instanceof ClassType) {
			   ClassType clazz = (ClassType)refType;
			   value = clazz.invokeMethod(threadRef, matchedMethod, args,
					ObjectReference.INVOKE_SINGLE_THREADED);
		    } else {
		    	value = obj.invokeMethod(threadRef, matchedMethod, args,
						ObjectReference.INVOKE_SINGLE_THREADED);
		    }
		} catch (InvalidTypeException e) {
			e.printStackTrace();
			throw new ExpressionEvalException("eval expression error, caused by :" + e.getMessage());
		} catch (ClassNotLoadedException e) {
			e.printStackTrace();
			throw new ExpressionEvalException("eval expression error, caused by :" + e.getMessage());
		} catch (IncompatibleThreadStateException e) {
			e.printStackTrace();
			throw new ExpressionEvalException("eval expression error, caused by :" + e.getMessage());
		} catch (InvocationException e) {
			e.printStackTrace();
			throw new ExpressionEvalException("eval expression error, caused by :" + e.getMessage());
		}
		return value;
	}
	private static Method findMatchedMethod(List<Method> methods, List arguments) {
		for (Method method : methods) {
			try {
				List argTypes = method.argumentTypes();
				if (argumentsMatch(argTypes, arguments))
					return method;
			} catch (ClassNotLoadedException e) {
			}
		}
		return null;
	}
	
	private static boolean argumentsMatch(List argTypes, List arguments) {
		if (argTypes.size() != arguments.size()) {
			return false;
		}
		Iterator typeIter = argTypes.iterator();
		Iterator valIter = arguments.iterator();
		while (typeIter.hasNext()) {
			Type argType = (Type) typeIter.next();
			Value value = (Value) valIter.next();
			if (value == null) {
				if (isPrimitiveType(argType.name()))
					return false;
			}
			if (!value.type().equals(argType)) {
				if (isAssignableTo(value.type(), argType)) {
					return true;
				} else {
					return false;
				}
			}
		}
		return true;
	}

	private static boolean isPrimitiveType(String name) {
		for (String primitiveType : primitiveTypeNames) {
			if (primitiveType.equals(name))
				return true;
		}
		return false;
	}
	private static boolean isAssignableTo(Type fromType, Type toType) {

		if (fromType.equals(toType))
			return true;
		if (fromType instanceof BooleanType && toType instanceof BooleanType)
			return true;
		if (toType instanceof BooleanType)
			return false;
		if (fromType instanceof PrimitiveType
				&& toType instanceof PrimitiveType)
			return true;
		if (toType instanceof PrimitiveType)
			return false;

		if (fromType instanceof ArrayType) {
			return isArrayAssignableTo((ArrayType) fromType, toType);
		}
		List interfaces;
		if (fromType instanceof ClassType) {
			ClassType superclazz = ((ClassType) fromType).superclass();
			if ((superclazz != null) && isAssignableTo(superclazz, toType)) {
				return true;
			}
			interfaces = ((ClassType) fromType).interfaces();
		} else {
			interfaces = ((InterfaceType) fromType).superinterfaces();
		}
		Iterator iter = interfaces.iterator();
		while (iter.hasNext()) {
			InterfaceType interfaze = (InterfaceType) iter.next();
			if (isAssignableTo(interfaze, toType)) {
				return true;
			}
		}
		return false;
	}

	static boolean isArrayAssignableTo(ArrayType fromType, Type toType) {
		if (toType instanceof ArrayType) {
			try {
				Type toComponentType = ((ArrayType) toType).componentType();
				return isComponentAssignable(fromType.componentType(),
						toComponentType);
			} catch (ClassNotLoadedException e) {
				return false;
			}
		}
		if (toType instanceof InterfaceType) {
			return toType.name().equals("java.lang.Cloneable");
		}
		return toType.name().equals("java.lang.Object");
	}

	private static boolean isComponentAssignable(Type fromType, Type toType) {
		if (fromType instanceof PrimitiveType) {
			return fromType.equals(toType);
		}
		if (toType instanceof PrimitiveType) {
			return false;
		}
		return isAssignableTo(fromType, toType);
	}

}
