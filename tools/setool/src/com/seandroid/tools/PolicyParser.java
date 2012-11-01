package com.seandroid.tools;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class PolicyParser {

    // All signautre based policy stanzas
    private static HashMap<String, InstallPolicy> mInstallSignaturePolicy =
        new HashMap<String, InstallPolicy>();

    // All package name based policy stanzas
    private static HashMap<String, InstallPolicy> mInstallPackagePolicy =
        new HashMap<String, InstallPolicy>();

    /**
     * Parses a mac_permissions.xml file and determines its internal policy
     * structure. All errors are treated as exceptions and are thrown
     * as Exception class. 
     */
    public static void PolicyStart(File policyFile) throws Exception {

        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
        Document doc = dBuilder.parse(policyFile);

        NodeList nList = doc.getElementsByTagName(PolicyBuilder.POLICY);
        if (nList == null)
            throw new Exception("No " + PolicyBuilder.POLICY + " root tag found.");

        if (nList.getLength() > 1)
            System.err.println("Multiple " + PolicyBuilder.POLICY + " tags " +
                                 "found. Using the first to determine policy.");

        Node nNode = nList.item(0);

        NodeList policyChildren = nNode.getChildNodes();
        for (int i = 0; i < policyChildren.getLength(); ++i) {
            Node nNoded = policyChildren.item(i);
            if (nNoded.getNodeType() == Node.ELEMENT_NODE) {
                Element f = (Element)nNoded;
                String tagName = f.getTagName();
                if (PolicyBuilder.SIGNER.equals(tagName)) {
                    String signer = f.getAttribute(PolicyBuilder.SIGNATURE_ATTR);
                    if (signer == null)
                        continue;
                    InstallPolicy type = determineInstallPolicyType(nNoded, true);
                    if (type != null)
                        mInstallSignaturePolicy.put(signer, type);
                } else if (PolicyBuilder.DEFAULT.equals(tagName)) {
                    InstallPolicy type = determineInstallPolicyType(nNoded, true);
                    if (type != null)
                        mInstallSignaturePolicy.put(null, type);
                } else if (PolicyBuilder.PACKAGE.equals(tagName)) {
                    String name = f.getAttribute(PolicyBuilder.NAME_ATTR);
                    if (name == null)
                        continue;
                    InstallPolicy type = determineInstallPolicyType(nNoded, false);
                    if (type != null)
                        mInstallPackagePolicy.put(name, type);
                }
            }
        }    
    }

    private static InstallPolicy determineInstallPolicyType(Node node, boolean notInsidePackageTag) {

        final HashSet<String> allowPolicyPerms = new HashSet<String>();
        final HashSet<String> denyPolicyPerms = new HashSet<String>();
        
        final HashMap<String, InstallPolicy> packagePolicy =
            new HashMap<String, InstallPolicy>();
        
        boolean allowAll = false;
        NodeList policyChildren = node.getChildNodes();
        for (int i = 0; i < policyChildren.getLength(); ++i) {
            Node nNoded = policyChildren.item(i);
            if (nNoded.getNodeType() == Node.ELEMENT_NODE) {
                Element f = (Element)nNoded;
                String tagName = f.getTagName();
                if (PolicyBuilder.ALLOW_PERMS.equals(tagName)) {
                    String permName = f.getAttribute(PolicyBuilder.NAME_ATTR);
                    if (permName != null)
                        allowPolicyPerms.add(permName);
                } else if (PolicyBuilder.DENY_PERMS.equals(tagName)) {
                    String permName = f.getAttribute(PolicyBuilder.NAME_ATTR);
                    if (permName != null)
                        denyPolicyPerms.add(permName);
                } else if (PolicyBuilder.ALLOW_ALL.equals(tagName)) {
                    allowAll = true;
                } else if (notInsidePackageTag && PolicyBuilder.PACKAGE.equals(tagName)) {
                    String pkgName = f.getAttribute(PolicyBuilder.NAME_ATTR);
                    if (pkgName == null)
                        continue;
                    InstallPolicy packageType = determineInstallPolicyType(nNoded, false);
                    if (packageType != null)
                        packagePolicy.put(pkgName, packageType);
                }
            }
        }
        
        InstallPolicy permPolicyType = null;
        if (denyPolicyPerms.size() > 0)
            permPolicyType = new BlackListPolicy(denyPolicyPerms, packagePolicy);
        else if (allowPolicyPerms.size() > 0)
            permPolicyType = new WhiteListPolicy(allowPolicyPerms, packagePolicy);
        else if (allowAll)
            permPolicyType = new InstallPolicy(null, packagePolicy);
        
        return permPolicyType;
    }
    

    /**
     * Policy checks work as follows:
     * We check all signature based policy first. If any of the signature based
     * checks pass then we accept the app. If none pass then we further check
     * for any package name or default based stanzas. Package names are checked
     * first then default policy.
     */
    public static boolean passedPolicy(Set<String> sigs, Set<String> perms, String name) {
        
        // signature based policy
        String sigErrorMessage = null;
        for (String s : sigs) {
            if (s == null) {
                continue;
            }
            
            if (mInstallSignaturePolicy.containsKey(s)) {
                InstallPolicy policy = mInstallSignaturePolicy.get(s);
                boolean passed = policy.passedPolicyChecks(name, perms);
                if (passed) {
                    return true;
                }
                sigErrorMessage = "\nSignautre based policy used\n" + policy.policyError;
            }
        }    
        
        // package name based policy
        if (mInstallPackagePolicy.containsKey(name)) {
            InstallPolicy policy = mInstallPackagePolicy.get(name);
            boolean passed = policy.passedPolicyChecks(name, perms);
            if (!passed) {
                System.err.println("\nPackage name policy stanza used.\n" +
                                   policy.policyError);
            }
            return passed;
        }

        // default policy
        if (mInstallSignaturePolicy.containsKey(null)) {
            InstallPolicy policy = mInstallSignaturePolicy.get(null);
            boolean passed = policy.passedPolicyChecks(name, perms);
            if (!passed) {
                System.err.println("\nDefault policy stanza used.\n" +
                                   policy.policyError);
            }
            return passed;
        }

        // no package name or default policy so print the signature
        // based error message if one exists. Prints the last
        // signature based error message if multiple ones exist.
        if (sigErrorMessage != null) {
            System.err.println(sigErrorMessage);
        } else {
            System.err.println("\nPolicy rejected package " + name +
                               "\nNo policy stanza matched.");
        }

        return false;
    }

}