/*
Copyright (c) 2012 Fredrik Ehnbom

This software is provided 'as-is', without any express or implied
warranty. In no event will the authors be held liable for any damages
arising from the use of this software.

Permission is granted to anyone to use this software for any purpose,
including commercial applications, and to alter it and redistribute it
freely, subject to the following restrictions:

   1. The origin of this software must not be misrepresented; you must not
   claim that you wrote the original software. If you use this software
   in a product, an acknowledgment in the product documentation would be
   appreciated but is not required.

   2. Altered source versions must be plainly marked as such, and must not be
   misrepresented as being the original software.

   3. This notice may not be removed or altered from any source
   distribution.
*/
/* MODIFIED BY Nick Lloyd */

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SublimeJava
{

    private static String getInstancedType(Class<?> c, String gen, String ret, String[] templateParam)
    {
        if (gen.startsWith("class "))
            gen = gen.substring("class ".length());
        {
            // This is a bit odd, and I'm not sure it's correct. Seems "gen" returns an absolute
            // class path that isn't correct. I've only seen this for the specific class that is
            // in the repro case of issue #26, but it probably happens elsewhere too so could
            // have to be tweaked.
            //
            // In short, gen will list Tests.Tests$Foo whereas the correct type would be just
            // Tests$Foo
            String pat = "(\\w+\\.)+"+ Pattern.quote(ret);
            Pattern p = Pattern.compile(pat);
            Matcher m = p.matcher(gen);
            if (m.find())
            {
                gen = m.replaceAll(Matcher.quoteReplacement(ret));
            }
        }

        if (!gen.equals(ret))
        {
            boolean set = false;
            TypeVariable<?> tv[] = c.getTypeParameters();
            for (int i = 0; i < tv.length && i < templateParam.length; i++)
            {
                String pat = "((^|[<,\\s])+)" + tv[i].getName() +  "(([,\\s>]|$)+)";
                Pattern p = Pattern.compile(pat);
                Matcher m = p.matcher(gen);
                if (m.find())
                {
                    gen = m.replaceAll(Matcher.quoteReplacement(m.group(1) + templateParam[i] + m.group(3)));
                }
            }
            ret = gen;
        }
        return ret;
    }

    private static String[] getCompletion(Method m, String filter, String[] templateParam)
    {
        String str = m.getName();
        if (!str.startsWith(filter))
            return null;
        str += "(";
        String ins = str;
        int count = 1;
        Type[] generic = m.getGenericParameterTypes();
        Class[] normal = m.getParameterTypes();
        for (int i = 0; i < normal.length; i++)
        {
            if (count > 1)
            {
                str += ", ";
                ins += ", ";
            }

            String gen = generic[i].toString();
            String ret = normal[i].getName();
            ret = getInstancedType(m.getDeclaringClass(), gen, ret, templateParam);
            str += ret;
            ins += "${"+count + ":" + ret.replace("$", "\\$") + "}";
            count++;
        }
        str += ")\t" + getInstancedType(m.getDeclaringClass(), m.getGenericReturnType().toString(), m.getReturnType().getName(), templateParam);
        ins += ")";
        return new String[] {str, ins};
    }
    private static String[] getCompletion(Field f, String filter, String[] templateArgs)
    {
        String str = f.getName();
        if (!str.startsWith(filter))
            return null;

        String rep = str + "\t" + getInstancedType(f.getDeclaringClass(), f.getGenericType().toString(), f.getType().getName(), templateArgs);
        return new String[] {rep, str};
    }
    private static String[] getCompletion(Class clazz, String filter, String[] templateArgs)
    {
        return new String[] {clazz.getSimpleName() + "\tclass", clazz.getSimpleName()};
    }
    private static <T> String[] getCompletion(T t, String filter, String[] templateArgs)
    {
        if (t instanceof Method)
        {
            return getCompletion((Method)t, filter, templateArgs);
        }
        else if (t instanceof Field)
        {
            return getCompletion((Field)t, filter, templateArgs);
        }
        else if (t instanceof Class)
        {
            return getCompletion((Class)t, filter, templateArgs);
        }
        return null;
    }
    private static final String sep = ";;--;;";

    private static String getClassname(String pack, String clazz)
    {
        if (pack.endsWith(".*"))
        {
            return pack.substring(0, pack.length()-2) + "." + clazz;
        }
        else if (pack.length() != 0)
        {
            return pack + "$" + clazz;
        }
        return clazz;
    }

    private static final int STATIC_BIT = 1 << 0;
    private static final int PRIVATE_BIT = 1 << 1;
    private static final int PROTECTED_BIT = 1 << 2;
    private static final int PUBLIC_BIT = 1 << 3;

    private static <T> int getModifiers(T t)
    {
        int modifiers = 0;
        if (t instanceof Method)
        {
            Method m = (Method) t;
            modifiers = m.getModifiers();
        }
        else if (t instanceof Field)
        {
            Field f = (Field) t;
            modifiers = f.getModifiers();
        }
        else if (t instanceof Class)
        {
            Class c = (Class) t;
            modifiers = c.getModifiers();
        }
        int returnvalue = 0;
        if (Modifier.isStatic(modifiers))
            returnvalue |= STATIC_BIT;
        if (Modifier.isPrivate(modifiers))
            returnvalue |= PRIVATE_BIT;
        if (Modifier.isProtected(modifiers))
            returnvalue |= PROTECTED_BIT;
        if (Modifier.isPublic(modifiers))
            returnvalue |= PUBLIC_BIT;
        return returnvalue;
    }

    private static <T> void dumpCompletions(T[] arr, String filter, String[] templateArgs)
    {
        for (T t : arr)
        {
            String[] completion = getCompletion(t, filter, templateArgs);
            if (completion == null)
            {
                continue;
            }
            System.out.println(completion[0] + sep + completion[1] + sep + getModifiers(t));
        }
    }

    private static boolean getReturnType(Field[] fields, String filter, String templateParam[])
    {
        for (Field f : fields)
        {
            if (filter.equals(f.getName()))
            {
                String gen = f.getGenericType().toString();
                String ret = f.getType().getName();
                ret = getInstancedType(f.getDeclaringClass(), gen, ret, templateParam);
                System.out.println("" + ret);
                return true;
            }
        }
        return false;
    }

    private static boolean getReturnType(Method[] methods, String filter, String templateParam[])
    {
        for (Method m : methods)
        {
            if (filter.equals(m.getName()))
            {
                String gen = m.getGenericReturnType().toString();
                String ret = m.getReturnType().getName();
                ret = getInstancedType(m.getDeclaringClass(), gen, ret, templateParam);
                System.out.println("" + ret);
                return true;
            }
        }
        return false;
    }
    private static boolean getReturnType(Class[] classes, String filter, String templateParam[])
    {
        for (Class clazz : classes)
        {
            if (filter.equals(clazz.getSimpleName()))
            {
                System.out.println(clazz.getName());
                return true;
            }
        }
        return false;
    }

    public static void main(String... unusedargs)
    {
        Map<String,PackageExposingClassLoader> classLoadersByProjectPath = new Hashtable<String,PackageExposingClassLoader>();

        try
        {
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
            boolean first = true;
            while (true)
            {
                try
                {
                    if (!first)
                        // Just to indicate that there's no more output from the command and we're ready for new input
                        System.out.println(";;--;;");
                    first = false;
                    String cmd = in.readLine();
                    if (cmd == null)
                        break;
                    String args[] = cmd.split(";;--;;");
                    System.err.println(args.length);
                    for (int i = 0; i < args.length; i++)
                    {
                        System.err.println(args[i]);
                    }

                    try
                    {
                        if (args[0].equals("-quit"))
                        {
                            System.err.println("quitting upon request");
                            return;
                        }
                        else if (args[0].equals("-separator"))
                        {
                            System.out.println(System.getProperty("path.separator"));
                            continue;
                        }
                        else if (args[0].equals("-haveclasspath"))
                        {
                            // TODO
                        }
                        else if (args[0].equals("-newclasspath"))
                        {
                            // TODO
                        }
                        else if (args[0].equals("-useclasspath"))
                        {
                            // TODO
                        }
                        else if (args[0].equals("-findclass"))
                        {
                            String line = null;
                            ArrayList<String> packages = new ArrayList<String>();
                            try
                            {
                                while ((line = in.readLine()) != null)
                                {
                                    if (line.compareTo(sep) == 0)
                                        break;
                                    packages.add(line);
                                }
                            }
                            catch (Exception e)
                            {
                            }
                            boolean found = false;
                            for (String pack : packages)
                            {
                                try
                                {
                                    String classname = getClassname(pack, args[1]);
                                    System.err.println("Testing for: " + classname);
                                    Class c = Class.forName(classname);
                                    System.out.println("" + c.getName());
                                    found = true;
                                    break;
                                }
                                catch (Exception e)
                                {
                                }
                            }
                            if (found)
                                continue;
                            // Still haven't found anything, so try to see if it's an internal class
                            for (String pack : packages)
                            {
                                String classname = getClassname(pack, args[1]);
                                while (!found && classname.indexOf('.') != -1)
                                {
                                    int idx = classname.lastIndexOf('.');
                                    classname = classname.substring(0, idx) + "$" + classname.substring(idx+1);
                                    try
                                    {
                                        System.err.println("Testing for: " + classname);
                                        Class c = Class.forName(classname);
                                        System.out.println("" + c.getName());
                                        found = true;
                                        break;
                                    }
                                    catch (Exception e)
                                    {
                                    }
                                }
                                if (found)
                                    break;
                            }
                            continue;
                        }
                        if (args.length < 2)
                            continue;
                        Class<?> c = Class.forName(args[1]);
                        String filter = "";
                        if (args.length >= 3)
                        {
                            filter = args[2];
                            if (filter.equals(sep))
                            {
                                filter = "";
                            }
                        }
                        int len = args.length - 3;
                        if (len < 0)
                            len = 0;
                        String[] templateParam = new String[len];
                        for (int i = 0; i < len; i++)
                        {
                            templateParam[i] = args[i+3];
                        }
                        if (args[0].equals("-complete"))
                        {
                            dumpCompletions(c.getFields(), filter, templateParam);
                            dumpCompletions(c.getDeclaredFields(), filter, templateParam);
                            dumpCompletions(c.getMethods(), filter, templateParam);
                            dumpCompletions(c.getDeclaredMethods(), filter, templateParam);
                            dumpCompletions(c.getClasses(), filter, templateParam);
                            dumpCompletions(c.getDeclaredClasses(), filter, templateParam);
                        }
                        else if (args[0].equals("-returntype"))
                        {
                            if (getReturnType(c.getDeclaredFields(), filter, templateParam))
                                continue;
                            if (getReturnType(c.getDeclaredMethods(), filter, templateParam))
                                continue;
                            if (getReturnType(c.getDeclaredClasses(), filter, templateParam))
                                continue;
                            if (getReturnType(c.getFields(), filter, templateParam))
                                continue;
                            if (getReturnType(c.getMethods(), filter, templateParam))
                                continue;
                            if (getReturnType(c.getClasses(), filter, templateParam))
                                continue;
                        }
                    }
                    catch (ClassNotFoundException x)
                    {
                    }
                }
                catch (Exception e)
                {
                    System.err.println("Exception caught: " + e.getMessage());
                    e.printStackTrace(System.err);
                }
            }
        }
        catch (Exception e)
        {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace(System.err);
        }
    }

    private static class PackageExposingClassLoader extends URLClassLoader {

        public PackageExposingClassLoader(URL[] urls)
        {
            super(urls);
        }

        public List<String> getPackageNames()
        {
            Package[] packages = getPackages();
            List<String> packageNames = new ArrayList<String>(packages.length);
            for(Package pkg : packages)
            {
                packageNames.add(pkg.getName());
            }

            return packageNames;
        }
    }
}
