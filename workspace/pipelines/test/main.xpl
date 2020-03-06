<?xml version="1.0" encoding="UTF-8"?>
<p:declare-step xmlns:p="http://www.w3.org/ns/xproc"
                xmlns:c="http://www.w3.org/ns/xproc-step"
                xmlns:opt="http://www.options.net/"
                version="1.0">
  <p:option name="source-dir" required="true"/>
  <p:option name="result-dir" required="true"/>
  <p:load name="read-from-input">
    <p:with-option name="href" select="concat($source-dir,'document.xml')"/>
  </p:load>
  <p:identity/>
  <p:store name="store-to-output">
    <p:with-option name="href" select="concat($result-dir,'document2.xml')"/>
  </p:store>
</p:declare-step>
