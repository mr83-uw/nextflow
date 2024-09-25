#!/usr/bin/env nextflow

params.db = "$baseDir/blast-db/tiny"
params.query = "$baseDir/data/sample.fa"
params.chunkSize = 1

process blast {
    input:
    path 'seq.fa'
    path db

    output:
    path 'out'

    """
    blastp -db $db -query seq.fa -outfmt 6 > out
    """
}

process sort {
    input:
    path 'hits_*'

    output:
    stdout

    """
    sort hits_*
    """
}


workflow {
    ch_fasta = Channel.fromPath(params.query)
        | splitFasta( by: params.chunkSize, file:true )

    blast(ch_fasta, file(params.db))
        | collect
        | sort
        | subscribe { hits -> println hits }
}
